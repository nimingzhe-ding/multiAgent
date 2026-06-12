# Local env files

1. Copy `local.env.example.ps1` to `local.env.local.ps1`.
2. Fill in MySQL, DashScope, and Feishu values.
3. Load it in PowerShell before starting the app:

```powershell
. .\env\local.env.local.ps1
mvn spring-boot:run
```

Files matching `*.local.ps1` are ignored by Git so secrets stay local.
