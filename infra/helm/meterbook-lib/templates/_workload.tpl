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
            {{- if .Values.managementPort }}
            # Health and metrics only; the Service never exposes it.
            - name: management
              containerPort: {{ .Values.managementPort }}
              protocol: TCP
            {{- end }}
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
              port: {{ .Values.probes.port | default "http" }}
            periodSeconds: 5
            failureThreshold: {{ .Values.probes.startup.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: {{ .Values.probes.port | default "http" }}
            periodSeconds: 10
            timeoutSeconds: 2
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: {{ .Values.probes.port | default "http" }}
            periodSeconds: 5
            timeoutSeconds: 2
            failureThreshold: 3
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            {{- range .Values.writablePaths }}
            - name: {{ .name }}
              mountPath: {{ .path }}
            {{- end }}
            {{- range .Values.configFiles }}
            - name: {{ .name }}
              mountPath: {{ .path }}
              readOnly: true
            {{- end }}
      volumes:
        # The root filesystem is read-only; /tmp and the declared writablePaths are the only writable paths.
        - name: tmp
          emptyDir:
            sizeLimit: {{ .Values.tmpSizeLimit }}
        {{- range .Values.writablePaths }}
        - name: {{ .name }}
          emptyDir:
            sizeLimit: {{ .sizeLimit | default "64Mi" }}
        {{- end }}
        {{- range .Values.configFiles }}
        - name: {{ .name }}
          configMap:
            name: {{ .configMap }}
        {{- end }}
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
Ingress is denied except from the same namespace and any namespaces listed explicitly. Egress stays open for now
(DNS, AWS APIs).
In the ambient mesh (ADR-0011) traffic from those peers arrives over mTLS on port 15008 (HBONE), and kubelet probes
come from the link-local address Istio rewrites them to; both are allowed. Strict mTLS rejects any plaintext the
policy lets through.
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
        - port: 15008
          protocol: TCP
      from:
        - podSelector: {}
        {{- range .Values.networkPolicy.allowFromNamespaces }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
        {{- end }}
    - from:
        - ipBlock:
            cidr: 169.254.7.127/32
{{- end }}
{{- end }}
