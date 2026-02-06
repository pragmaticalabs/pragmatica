#!/usr/bin/env python3
"""Train a Tiny Time Mixer model for Aether predictive scaling.

Input: CSV with columns matching FeatureIndex (11 features).
Output: PyTorch model checkpoint.

Usage:
    python train.py --data metrics.csv --epochs 100 --output model.pt
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, TensorDataset


FEATURE_NAMES = [
    "cpu_usage", "heap_usage", "event_loop_lag_ms", "latency_ms", "invocations",
    "gc_pause_ms", "latency_p50", "latency_p95", "latency_p99", "error_rate", "event_count"
]
NUM_FEATURES = len(FEATURE_NAMES)


class TinyTimeMixer(nn.Module):
    """Lightweight time-series forecasting model.

    Architecture: temporal mixing + channel mixing (inspired by TSMixer/TTM).
    Input:  [batch, seq_len, features]
    Output: [batch, horizon, features]
    """

    def __init__(self, seq_len: int, horizon: int, features: int, d_model: int = 64, n_layers: int = 3):
        super().__init__()
        self.seq_len = seq_len
        self.horizon = horizon
        self.features = features

        # Input projection
        self.input_proj = nn.Linear(features, d_model)

        # Mixer layers (temporal + channel mixing)
        self.layers = nn.ModuleList()
        for _ in range(n_layers):
            self.layers.append(nn.ModuleDict({
                "temporal_norm": nn.LayerNorm(d_model),
                "temporal_mix": nn.Sequential(
                    nn.Linear(seq_len, seq_len),
                    nn.GELU(),
                    nn.Dropout(0.1),
                    nn.Linear(seq_len, seq_len),
                ),
                "channel_norm": nn.LayerNorm(d_model),
                "channel_mix": nn.Sequential(
                    nn.Linear(d_model, d_model * 4),
                    nn.GELU(),
                    nn.Dropout(0.1),
                    nn.Linear(d_model * 4, d_model),
                ),
            }))

        # Output projection
        self.output_proj = nn.Sequential(
            nn.LayerNorm(d_model),
            nn.Linear(d_model, features),
        )

        # Temporal output mapping: seq_len -> horizon
        self.temporal_out = nn.Linear(seq_len, horizon)

    def forward(self, x):
        # x: [batch, seq_len, features]
        x = self.input_proj(x)  # [batch, seq_len, d_model]

        for layer in self.layers:
            # Temporal mixing (mix across time steps)
            residual = x
            x = layer["temporal_norm"](x)
            x = x.permute(0, 2, 1)  # [batch, d_model, seq_len]
            x = layer["temporal_mix"](x)
            x = x.permute(0, 2, 1)  # [batch, seq_len, d_model]
            x = x + residual

            # Channel mixing (mix across features)
            residual = x
            x = layer["channel_norm"](x)
            x = layer["channel_mix"](x)
            x = x + residual

        # Project to features
        x = self.output_proj(x)  # [batch, seq_len, features]

        # Map temporal dimension: seq_len -> horizon
        x = x.permute(0, 2, 1)  # [batch, features, seq_len]
        x = self.temporal_out(x)  # [batch, features, horizon]
        x = x.permute(0, 2, 1)  # [batch, horizon, features]
        return x


def load_data(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    # Ensure all feature columns exist
    missing = [f for f in FEATURE_NAMES if f not in df.columns]
    if missing:
        print(f"ERROR: Missing columns: {missing}", file=sys.stderr)
        sys.exit(1)
    return df


def normalize(data: np.ndarray):
    """Min-max normalize per feature. Returns (normalized, mins, maxs)."""
    mins = data.min(axis=0)
    maxs = data.max(axis=0)
    ranges = maxs - mins
    ranges[ranges == 0] = 1.0  # avoid division by zero
    return (data - mins) / ranges, mins, maxs


def create_sequences(data: np.ndarray, seq_len: int, horizon: int):
    """Create sliding window sequences for training."""
    xs, ys = [], []
    for i in range(len(data) - seq_len - horizon + 1):
        xs.append(data[i:i + seq_len])
        ys.append(data[i + seq_len:i + seq_len + horizon])
    return np.array(xs), np.array(ys)


def main():
    parser = argparse.ArgumentParser(description="Train TTM model")
    parser.add_argument("--data", required=True, help="Input CSV file")
    parser.add_argument("--epochs", type=int, default=100, help="Training epochs (default: 100)")
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size (default: 32)")
    parser.add_argument("--learning-rate", type=float, default=1e-4, help="Learning rate (default: 1e-4)")
    parser.add_argument("--seq-len", type=int, default=60, help="Input window minutes (default: 60)")
    parser.add_argument("--horizon", type=int, default=1, help="Prediction horizon minutes (default: 1)")
    parser.add_argument("--validation-split", type=float, default=0.2, help="Validation split (default: 0.2)")
    parser.add_argument("--output", "-o", default="model.pt", help="Output model file")
    args = parser.parse_args()

    print(f"Loading data from {args.data}...")
    df = load_data(args.data)
    features = df[FEATURE_NAMES].values.astype(np.float32)
    print(f"  {len(features)} samples, {NUM_FEATURES} features")

    # Normalize
    features_norm, feat_mins, feat_maxs = normalize(features)

    # Create sequences
    X, Y = create_sequences(features_norm, args.seq_len, args.horizon)
    print(f"  {len(X)} sequences (seq_len={args.seq_len}, horizon={args.horizon})")

    if len(X) < 10:
        print("ERROR: Not enough data for training. Need at least seq_len + horizon + 10 rows.", file=sys.stderr)
        sys.exit(1)

    # Train/validation split
    split_idx = int(len(X) * (1 - args.validation_split))
    X_train, X_val = X[:split_idx], X[split_idx:]
    Y_train, Y_val = Y[:split_idx], Y[split_idx:]
    print(f"  Training: {len(X_train)}, Validation: {len(X_val)}")

    # PyTorch datasets
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"  Device: {device}")

    train_ds = TensorDataset(torch.from_numpy(X_train), torch.from_numpy(Y_train))
    val_ds = TensorDataset(torch.from_numpy(X_val), torch.from_numpy(Y_val))
    train_dl = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True)
    val_dl = DataLoader(val_ds, batch_size=args.batch_size)

    # Model
    model = TinyTimeMixer(args.seq_len, args.horizon, NUM_FEATURES).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    criterion = nn.MSELoss()
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, args.epochs)

    param_count = sum(p.numel() for p in model.parameters())
    print(f"  Model parameters: {param_count:,}")

    # Training loop
    best_val_loss = float("inf")
    for epoch in range(1, args.epochs + 1):
        model.train()
        train_loss = 0.0
        for xb, yb in train_dl:
            xb, yb = xb.to(device), yb.to(device)
            pred = model(xb)
            loss = criterion(pred, yb)
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            train_loss += loss.item() * len(xb)
        train_loss /= len(X_train)
        scheduler.step()

        # Validation
        model.eval()
        val_loss = 0.0
        with torch.no_grad():
            for xb, yb in val_dl:
                xb, yb = xb.to(device), yb.to(device)
                pred = model(xb)
                val_loss += criterion(pred, yb).item() * len(xb)
        val_loss /= len(X_val)

        if epoch % 10 == 0 or epoch == 1:
            print(f"  Epoch {epoch:4d}/{args.epochs}: train_loss={train_loss:.6f} val_loss={val_loss:.6f}")

        if val_loss < best_val_loss:
            best_val_loss = val_loss
            torch.save({
                "model_state_dict": model.state_dict(),
                "seq_len": args.seq_len,
                "horizon": args.horizon,
                "features": NUM_FEATURES,
                "feature_names": FEATURE_NAMES,
                "normalization": {"mins": feat_mins.tolist(), "maxs": feat_maxs.tolist()},
                "best_val_loss": best_val_loss,
            }, args.output)

    print(f"\nTraining complete. Best validation loss: {best_val_loss:.6f}")
    print(f"Model saved to {args.output}")


if __name__ == "__main__":
    main()
