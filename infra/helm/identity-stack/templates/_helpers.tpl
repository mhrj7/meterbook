{{/* identity-stack helpers. The environment decides database and role names (one per environment, ADR-0007). */}}
{{- define "identity-stack.env" -}}
{{- $env := required "environment is required (dev, staging or prod)" .Values.environment -}}
{{- if not (has $env (list "dev" "staging" "prod")) }}{{ fail "environment must be dev, staging or prod" }}{{ end -}}
{{- $env -}}
{{- end }}

{{- define "identity-stack.labels" -}}
app.kubernetes.io/part-of: meterbook
app.kubernetes.io/managed-by: {{ .Release.Service }}
meterbook.io/environment: {{ include "identity-stack.env" . }}
{{- end }}
