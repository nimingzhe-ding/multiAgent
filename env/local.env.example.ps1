# Copy this file to env/local.env.local.ps1, then fill in your real values.
# Run it before starting the app:
#   . .\env\local.env.local.ps1

# MySQL
$env:DB_JDBC_URL = "jdbc:mysql://localhost:3306/super_biz_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "YOUR_MYSQL_PASSWORD"

# DashScope
$env:DASHSCOPE_API_KEY = "YOUR_DASHSCOPE_API_KEY"

# Feishu custom group bot
$env:FEISHU_WEBHOOK_URL = "https://open.feishu.cn/open-apis/bot/v2/hook/YOUR_WEBHOOK_TOKEN"
$env:FEISHU_WEBHOOK_SECRET = "YOUR_FEISHU_SIGN_SECRET"
$env:FEISHU_TIMEOUT_SECONDS = "10"

# Memory monitor
$env:MEMORY_MONITOR_CHECK_INTERVAL_MS = "60000"

# Elasticsearch keyword retrieval
$env:ELASTICSEARCH_KEYWORD_ENABLED = "true"
$env:ELASTICSEARCH_URL = "http://localhost:9200"
$env:ELASTICSEARCH_KNOWLEDGE_INDEX = "super_biz_knowledge"
$env:ELASTICSEARCH_USERNAME = ""
$env:ELASTICSEARCH_PASSWORD = ""

# MCP loads on app startup. Keep false until you paste a working MCP config into application.yml.
$env:MCP_CLIENT_ENABLED = "false"
$env:APP_MCP_TOOLS_ENABLED = "false"

# Console encoding for Windows PowerShell logs.
$env:CONSOLE_LOG_CHARSET = "GBK"
