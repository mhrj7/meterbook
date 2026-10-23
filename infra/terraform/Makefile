# Local checks for the Terraform tree. CI runs the same targets (MB-T05).
# Requires: terraform >= 1.10, tflint, trivy.

ROOTS   := bootstrap envs/dev envs/prod
MODULES := $(wildcard modules/*)

.PHONY: check fmt fmt-fix validate test lint scan

check: fmt validate test lint scan

fmt:
	terraform fmt -check -recursive -diff

fmt-fix:
	terraform fmt -recursive

validate:
	@for d in $(ROOTS) $(MODULES); do \
		echo "== validate $$d"; \
		(cd $$d && terraform init -backend=false -input=false >/dev/null && terraform validate) || exit 1; \
	done

test:
	@for d in $(MODULES); do \
		if [ -d $$d/tests ]; then \
			echo "== test $$d"; \
			(cd $$d && terraform init -backend=false -input=false >/dev/null && terraform test) || exit 1; \
		fi; \
	done

lint:
	tflint --init --config $(CURDIR)/.tflint.hcl
	tflint --recursive --config $(CURDIR)/.tflint.hcl

scan:
	trivy config --severity HIGH,CRITICAL --exit-code 1 .
