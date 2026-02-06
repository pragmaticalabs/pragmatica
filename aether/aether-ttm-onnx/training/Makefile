.PHONY: setup synthetic train export validate all docker-train clean

VENV := .venv
PYTHON := $(VENV)/bin/python
PIP := $(VENV)/bin/pip
DATA ?= synthetic.csv
MODEL ?= model.pt
ONNX_MODEL ?= ttm-aether.onnx
EPOCHS ?= 100
SEQ_LEN ?= 60
HORIZON ?= 1

setup: $(VENV)/bin/activate

$(VENV)/bin/activate: requirements.txt
	python3 -m venv $(VENV)
	$(PIP) install -r requirements.txt
	touch $@

synthetic: setup
	$(PYTHON) generate_synthetic.py --pattern mixed --duration 48h --output $(DATA)

train: setup
	$(PYTHON) train.py --data $(DATA) --epochs $(EPOCHS) --seq-len $(SEQ_LEN) --horizon $(HORIZON) --output $(MODEL)

export: setup
	$(PYTHON) export_onnx.py --model $(MODEL) --output $(ONNX_MODEL)

validate: setup
	$(PYTHON) export_onnx.py --validate $(ONNX_MODEL)

all: synthetic train export validate

docker-train:
	docker build -t ttm-training -f Dockerfile.training .
	docker run --rm -v $(PWD):/data ttm-training train.py --data /data/$(DATA) --epochs $(EPOCHS) --output /data/$(MODEL)
	docker run --rm -v $(PWD):/data ttm-training export_onnx.py --model /data/$(MODEL) --output /data/$(ONNX_MODEL)

clean:
	rm -rf $(VENV) __pycache__ *.pyc $(MODEL) $(ONNX_MODEL)
