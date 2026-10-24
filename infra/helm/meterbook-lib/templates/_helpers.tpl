{{/* Name of the service: values.name, else the release name. */}}
{{- define "meterbook-lib.name" -}}
{{- default .Release.Name .Values.name | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "meterbook-lib.selectorLabels" -}}
app.kubernetes.io/name: {{ include "meterbook-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "meterbook-lib.labels" -}}
{{ include "meterbook-lib.selectorLabels" . }}
app.kubernetes.io/version: {{ include "meterbook-lib.imageVersion" . | quote }}
app.kubernetes.io/part-of: meterbook
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end }}

{{/* Image reference: digest wins over tag. */}}
{{- define "meterbook-lib.image" -}}
{{- $img := .Values.image -}}
{{- if $img.digest -}}
{{ $img.repository }}@{{ $img.digest }}
{{- else -}}
{{ $img.repository }}:{{ $img.tag }}
{{- end -}}
{{- end }}

{{- define "meterbook-lib.imageVersion" -}}
{{- /* A label value cannot contain ':', so a digest-only image is labelled with its first 12 hex characters. */ -}}
{{- if .Values.image.tag }}{{ .Values.image.tag }}{{ else }}{{ .Values.image.digest | trimPrefix "sha256:" | trunc 12 }}{{ end -}}
{{- end }}

{{/*
Guard rails. Every service renders this first; a values file cannot produce an unsafe workload.
*/}}
{{- define "meterbook-lib.validate" -}}
{{- $img := .Values.image -}}
{{- if not $img.repository }}{{ fail "image.repository is required" }}{{ end -}}
{{- if and (not $img.tag) (not $img.digest) }}{{ fail "set image.digest (preferred) or image.tag" }}{{ end -}}
{{- if eq (toString $img.tag) "latest" }}{{ fail "image.tag must not be 'latest': deployments must be reproducible" }}{{ end -}}
{{- if and $img.digest (not (hasPrefix "sha256:" $img.digest)) }}{{ fail "image.digest must start with sha256:" }}{{ end -}}
{{- if le (int .Values.securityContext.runAsUser) 0 }}{{ fail "securityContext.runAsUser must be a non-root UID (> 0)" }}{{ end -}}
{{- if not .Values.resources.requests }}{{ fail "resources.requests are required" }}{{ end -}}
{{- if not (and .Values.resources.limits .Values.resources.limits.memory) }}{{ fail "resources.limits.memory is required" }}{{ end -}}
{{- if and .Values.autoscaling.enabled (lt (int .Values.autoscaling.maxReplicas) (int .Values.autoscaling.minReplicas)) }}{{ fail "autoscaling.maxReplicas must be >= minReplicas" }}{{ end -}}
{{- $probePort := .Values.probes.port | default "http" -}}
{{- if not (has $probePort (list "http" "management")) }}{{ fail "probes.port must be http or management" }}{{ end -}}
{{- if and (eq $probePort "management") (not .Values.managementPort) }}{{ fail "probes.port=management needs managementPort" }}{{ end -}}
{{- range concat (.Values.writablePaths | default list) (.Values.configFiles | default list) -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$" (toString .name)) }}{{ fail (printf "volume name %q must be a DNS label" (toString .name)) }}{{ end -}}
{{- if eq (toString .name) "tmp" }}{{ fail "volume name tmp is reserved" }}{{ end -}}
{{- if not (hasPrefix "/" (toString .path)) }}{{ fail (printf "volume %s needs an absolute path" .name) }}{{ end -}}
{{- end -}}
{{- end }}
