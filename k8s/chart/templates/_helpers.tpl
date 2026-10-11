{{/* 모든 리소스에 붙는 표. 이름은 차트 밖(앱 설정의 `db`·`redis`·`kafka`)이 부르므로 릴리스 이름을 안 붙인다. */}}
{{- define "ticket.labels" -}}
app.kubernetes.io/part-of: ticket
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}
