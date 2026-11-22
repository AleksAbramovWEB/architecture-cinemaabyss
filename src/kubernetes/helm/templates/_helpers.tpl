{{- define "cinemaabyss.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cinemaabyss.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cinemaabyss.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cinemaabyss.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cinemaabyss.labels" -}}
helm.sh/chart: {{ include "cinemaabyss.chart" . }}
{{ include "cinemaabyss.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "cinemaabyss.namespace" -}}
{{- .Release.Namespace -}}
{{- end -}}

{{- define "cinemaabyss.databaseSecretName" -}}
{{- default "cinemaabyss-secrets" .Values.database.existingSecret -}}
{{- end -}}

{{- define "cinemaabyss.imagePullSecrets" -}}
{{- if .Values.imagePullSecrets.existingSecret -}}
imagePullSecrets:
  - name: {{ .Values.imagePullSecrets.existingSecret | quote }}
{{- else if .Values.imagePullSecrets.dockerconfigjson -}}
imagePullSecrets:
  - name: dockerconfigjson
{{- end -}}
{{- end -}}

{{- define "cinemaabyss.validate" -}}
{{- if and (empty .Values.database.existingSecret) (empty .Values.database.password) -}}
{{- fail "Set database.password or database.existingSecret" -}}
{{- end -}}
{{- if and .Values.kafka.enabled (not .Values.zookeeper.enabled) -}}
{{- fail "The bundled Kafka broker requires zookeeper.enabled=true" -}}
{{- end -}}
{{- if and .Values.eventsService.enabled (not .Values.kafka.enabled) (empty .Values.config.kafkaBrokers) -}}
{{- fail "Set config.kafkaBrokers when Events uses an external Kafka broker" -}}
{{- end -}}
{{- if and .Values.ingress.enabled (not .Values.proxyService.enabled) -}}
{{- fail "ingress.enabled requires proxyService.enabled=true" -}}
{{- end -}}
{{- if and .Values.ingress.enabled .Values.ingress.tls.enabled (empty .Values.ingress.tls.secretName) -}}
{{- fail "Set ingress.tls.secretName for HTTPS" -}}
{{- end -}}
{{- if .Values.imagePullSecrets.dockerconfigjson -}}
{{- $auth := .Values.imagePullSecrets.dockerconfigjson | b64dec | fromJson -}}
{{- if or (hasKey $auth "Error") (not (hasKey $auth "auths")) -}}
{{- fail "imagePullSecrets.dockerconfigjson must be base64-encoded Docker config JSON with auths" -}}
{{- end -}}
{{- end -}}
{{- end -}}
