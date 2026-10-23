# Local checks for the Terraform tree. CI runs the same targets (MB-T05).
# Requires: terraform >= 1.10, tflint, trivy.

ROOTS   := bootstrap envs/dev envs/prod
MODULES := $(wildcard modules/*)

.PHONY: check fmt fmt-fix validate test lint scan no-secret-values

check: fmt no-secret-values validate test lint scan

fmt:
	terraform fmt -check -recursive -diff

# Secret values are set out-of-band (ADR-0004); they must never pass through Terraform state.
no-secret-values:
	@if grep -rnE 'resource +"aws_(secretsmanager_secret_version|ssm_parameter)"' --include='*.tf' .; then \
		echo "Secret values must not be managed by Terraform (ADR-0004)."; exit 1; \
	fi
	@echo "no secret values in Terraform"

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
	trivy config --severity HIGH,CRITICAL --exit-code 1 --skip-dirs "**/.terraform" .
