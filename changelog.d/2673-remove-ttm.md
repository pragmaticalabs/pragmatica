### Removed (TTM predictive scaling, #2673)
- **BREAKING: TTM is removed entirely** (owner ruling R-8). The modules `aether-ttm` and `aether-ttm-onnx`, `TTMManager`, `AdaptiveDecisionTree`, `TtmConfig` and the ONNX predictor no longer exist. The feature was off by default, so default behaviour does not change.
- **BREAKING (config keys):** the `[ttm]` TOML section is gone: `ttm.enabled`, `ttm.model_path`, `ttm.input_window_minutes`, `ttm.prediction_horizon`, `ttm.confidence_threshold`. A `[ttm]` section left in a config file is ignored.
- **BREAKING (management routes):** `GET /api/v1/ttm/status` (`TTM_STATUS`) and `GET /api/v1/ttm/training-data` (`TTM_TRAINING_DATA`) are removed. `ManagementRoute` drops both entries.
- **BREAKING (CLI):** `aether ttm status` and `aether ttm training-data` (and the `aether ttm` group) are removed.
- **BREAKING (API):** the `ttm` slot is gone from `AetherConfig`, `AetherNodeConfig` (its staged builder goes from `cache` straight to `controllerConfig`; the convenience constructor loses the parameter) and `ManageableNode`; `MinuteAggregator.toTTMInput` is removed.
- Docs: `ttm-guide.md` and `contributors/ttm-integration.md` are deleted and the scaling docs describe the reactive decision tree only. The BSL module list loses the two modules.
