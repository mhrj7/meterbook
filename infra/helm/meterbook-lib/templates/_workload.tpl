{{- define "meterbook-lib.serviceaccount" -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "meterbook-lib.name" . }}
  labels:
    {{- include "meterbook-lib.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
automountServiceAccountToken: false
{{- end }}

{{- define "meterbook-lib.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "meterbook-lib.name" . }}
  labels:
    {{- include "meterbook-lib.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicas }}
  {{- end }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 25%
  selector:
    matchLabels:
      {{- include "meterbook-lib.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "meterbook-lib.labels" . | nindent 8 }}
      {{- with .Values.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    spec:
      serviceAccountName: {{ include "meterbook-lib.name" . }}
      automountServiceAccountToken: {{ .Values.serviceAccount.automountToken }}
      enableServiceLinks: false
      terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds }}
      securityContext:
        runAsNonRoot: true
        runAsUser: {{ .Values.securityContext.runAsUser }}
        runAsGroup: {{ .Values.securityContext.runAsUser }}
        fsGroup: {{ .Values.securityContext.runAsUser }}
        seccompProfile:
          type: RuntimeDefault
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                topologyKey: kubernetes.io/hostname
                labelSelector:
                  matchLabels:
                    {{- include "meterbook-lib.selectorLabels" . | nindent 20 }}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              {{- include "meterbook-lib.selectorLabels" . | nindent 14 }}
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              {{- include "meterbook-lib.selectorLabels" . | nindent 14 }}
      containers:
        - name: app
          image: {{ include "meterbook-lib.image" . }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          {{- with .Values.command }}
          command:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- with .Values.args }}
          args:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          ports:
            - name: http
              containerPort: {{ .Values.containerPort }}
              protocol: TCP
          {{- with .Values.env }}
          env:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- if .Values.envFromSecrets }}
          envFrom:
            {{- range .Values.envFromSecrets }}
            - secretRef:
                name: {{ . }}
            {{- end }}
          {{- end }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            privileged: false
            capabilities:
              drop: ["ALL"]
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          startupProbe:
            httpGet:
              path: {{ .Values.probes.startup.path }}
              port: http
            periodSeconds: 5
            failureThreshold: {{ .Values.probes.startup.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: http
            periodSeconds: 10
            timeoutSeconds: 2
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: http
            periodSeconds: 5
            timeoutSeconds: 2
            failureThreshold: 3
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        # The root filesystem is read-only; /tmp is the only writable path.
        - name: tmp
          emptyDir:
            sizeLimit: {{ .Values.tmpSizeLimit }}
{{- end }}

{{- define "meterbook-lib.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "meterbook-lib.name" . }}
  labels:
    {{- include "meterbook-lib.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  selector:
    {{- include "meterbook-lib.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
{{- end }}

{{- define "meterbook-lib.hpa" -}}
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "meterbook-lib.name" . }}
  labels:
    {{- include "meterbook-lib.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "meterbook-lib.name" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilization }}
{{- end }}
{{- end }}

{{- define "meterbook-lib.pdb" -}}
{{- if .Values.pdb.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "meterbook-lib.name" . }}
  labels:
    {{- include "meterbook-lib.labels" . | nindent 4 }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  # Pods that are not Ready can always be evicted, so a crash-looping pod never blocks a node drain.
  unhealthyPodEvictionPolicy: AlwaysAllow
  selector:
    matchLabels:
      {{- include "meterbook-lib.selectorLabels" . | nindent 6 }}
{{- end }}
{{- end }}

{{/*
Ingress is denied except from the same namespace and any namespaces listed explicitly.
Egress stays open for now (DNS, AWS APIs); egress policy follows with the service mesh (MB-T10).
*/}}
{{- define "meterbook-lib.networkpolicy" -}}
{{- if .Values.networkPolicy.enabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "meterbook-lib.name" . }}
  labels:
    {{- include "meterbook-lib.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "meterbook-lib.selectorLabels" . | nindent 6 }}
  policyTypes: ["Ingress"]
  ingress:
    - ports:
        - port: http
          protocol: TCP
      from:
        - podSelector: {}
        {{- range .Values.networkPolicy.allowFromNamespaces }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
        {{- end }}
{{- end }}
{{- end }}
