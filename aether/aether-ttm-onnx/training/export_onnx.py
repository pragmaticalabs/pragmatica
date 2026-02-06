#!/usr/bin/env python3
"""Export trained TTM model to ONNX format.

Usage:
    python export_onnx.py --model model.pt --output ttm-aether.onnx
    python export_onnx.py --validate ttm-aether.onnx
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import torch

# Import model class
from train import TinyTimeMixer, NUM_FEATURES


def export(model_path: str, output_path: str):
    print(f"Loading model from {model_path}...")
    checkpoint = torch.load(model_path, map_location="cpu", weights_only=False)

    seq_len = checkpoint["seq_len"]
    horizon = checkpoint["horizon"]
    features = checkpoint["features"]
    print(f"  seq_len={seq_len}, horizon={horizon}, features={features}")

    model = TinyTimeMixer(seq_len, horizon, features)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    # Export to ONNX
    dummy_input = torch.randn(1, seq_len, features)
    print(f"  Input shape: {list(dummy_input.shape)}")

    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={
            "input": {0: "batch_size"},
            "output": {0: "batch_size"},
        },
        opset_version=17,
    )
    print(f"  Exported to {output_path}")

    # Validate
    validate(output_path, seq_len, features, horizon)


def validate(onnx_path: str, seq_len: int = None, features: int = None, horizon: int = None):
    import onnx
    import onnxruntime as ort

    print(f"\nValidating {onnx_path}...")
    model = onnx.load(onnx_path)
    onnx.checker.check_model(model)
    print("  ONNX model is valid")

    # Check input/output names
    inputs = [i.name for i in model.graph.input]
    outputs = [o.name for o in model.graph.output]
    print(f"  Inputs: {inputs}")
    print(f"  Outputs: {outputs}")

    assert "input" in inputs, f"Expected input named 'input', got {inputs}"
    assert "output" in outputs, f"Expected output named 'output', got {outputs}"

    # Run inference test
    if seq_len and features:
        sess = ort.InferenceSession(onnx_path)
        test_input = np.random.randn(1, seq_len, features).astype(np.float32)
        result = sess.run(None, {"input": test_input})
        output = result[0]
        print(f"  Test inference: input={list(test_input.shape)} -> output={list(output.shape)}")

        if horizon:
            assert output.shape == (1, horizon, features), \
                f"Expected output shape (1, {horizon}, {features}), got {output.shape}"
            print("  Output shape matches expected dimensions")

    file_size = Path(onnx_path).stat().st_size
    print(f"  File size: {file_size / 1024:.1f} KB")
    print("  Validation passed!")


def main():
    parser = argparse.ArgumentParser(description="Export/validate TTM ONNX model")
    parser.add_argument("--model", help="PyTorch model checkpoint to export")
    parser.add_argument("--output", "-o", default="ttm-aether.onnx", help="Output ONNX file")
    parser.add_argument("--validate", metavar="ONNX_FILE", help="Validate an existing ONNX file")
    args = parser.parse_args()

    if args.validate:
        validate(args.validate)
    elif args.model:
        export(args.model, args.output)
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
