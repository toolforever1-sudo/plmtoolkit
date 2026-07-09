# Portkey Migration Guide

## What Changed

We switched from calling the **Anthropic API directly** to routing through **Portkey**, an AI gateway that connects to **Azure Foundry** (Microsoft-hosted Claude). This means:

- API calls go to `api.portkey.ai` instead of `api.anthropic.com`
- Authentication uses a **Portkey API key** instead of the Anthropic `sk-ant-*` key
- The request format changes from **Anthropic Messages API** to **OpenAI-compatible Chat Completions**
- The response format changes accordingly

Your Anthropic key (`sk-ant-api03-hGZ...RgAA`) is no longer needed for AI calls. It's still configured as a fallback but is currently disabled in the Anthropic Console.

## Your Portkey Credentials

| Field | Value |
|-------|-------|
| Portkey API Key | `hyXJhCcEVh1Cpp+d5WsEoqfEk2oc` |
| Provider | `@anthropic-eastus2` (Azure Foundry) |
| Available Model | `claude-sonnet-4-6` |
| Gateway URL | `https://api.portkey.ai/v1/chat/completions` |

> **Note:** Only `claude-sonnet-4-6` is enabled on your Azure Foundry integration. Haiku (`claude-haiku-4-5`) returned a 412 "model not allowed" error. You can add Haiku later in the Portkey dashboard under your AI Provider integration.

## How to Migrate a Python Script

### Before (Direct Anthropic)

```python
import requests

response = requests.post(
    "https://api.anthropic.com/v1/messages",
    headers={
        "x-api-key": "sk-ant-api03-hGZ...RgAA",
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
    },
    json={
        "model": "claude-sonnet-4-20250514",
        "max_tokens": 1024,
        "system": "You are a helpful assistant.",
        "messages": [
            {"role": "user", "content": "Hello, what is PLM?"}
        ]
    }
)

data = response.json()
answer = data["content"][0]["text"]
print(answer)
```

### After (Portkey via OpenAI-compatible format)

```python
import requests

response = requests.post(
    "https://api.portkey.ai/v1/chat/completions",
    headers={
        "x-portkey-api-key": "hyXJhCcEVh1Cpp+d5WsEoqfEk2oc",
        "Content-Type": "application/json",
    },
    json={
        "model": "@anthropic-eastus2/claude-sonnet-4-6",
        "max_tokens": 1024,
        "messages": [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Hello, what is PLM?"}
        ]
    }
)

data = response.json()
answer = data["choices"][0]["message"]["content"]
print(answer)
```

### Key Differences

| | Direct Anthropic | Portkey |
|---|---|---|
| **URL** | `https://api.anthropic.com/v1/messages` | `https://api.portkey.ai/v1/chat/completions` |
| **Auth header** | `x-api-key: sk-ant-...` | `x-portkey-api-key: hyXJhCcE...` |
| **Version header** | `anthropic-version: 2023-06-01` | Not needed |
| **Model** | `claude-sonnet-4-20250514` | `@anthropic-eastus2/claude-sonnet-4-6` |
| **System prompt** | Top-level `"system"` field | `{"role": "system"}` message in the `messages` array |
| **Response path** | `data["content"][0]["text"]` | `data["choices"][0]["message"]["content"]` |

### If Using the Anthropic Python SDK

```python
# Before
from anthropic import Anthropic
client = Anthropic(api_key="sk-ant-api03-hGZ...RgAA")

# After — use the OpenAI SDK with Portkey base URL
from openai import OpenAI
client = OpenAI(
    api_key="hyXJhCcEVh1Cpp+d5WsEoqfEk2oc",
    base_url="https://api.portkey.ai/v1",
    default_headers={"x-portkey-api-key": "hyXJhCcEVh1Cpp+d5WsEoqfEk2oc"}
)

response = client.chat.completions.create(
    model="@anthropic-eastus2/claude-sonnet-4-6",
    max_tokens=1024,
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Hello, what is PLM?"}
    ]
)
print(response.choices[0].message.content)
```

### Or Use the Portkey Python SDK

```bash
pip install portkey-ai
```

```python
from portkey_ai import Portkey

client = Portkey(api_key="hyXJhCcEVh1Cpp+d5WsEoqfEk2oc")

response = client.chat.completions.create(
    model="@anthropic-eastus2/claude-sonnet-4-6",
    max_tokens=1024,
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Hello, what is PLM?"}
    ]
)
print(response.choices[0].message.content)
```

## What Changed in PLM Toolkit (Java)

Six AI touchpoints were updated — all controlled by one config switch:

| Feature | File | What it does |
|---------|------|-------------|
| Help chatbot | `AiHelpController.java` | Answers user questions |
| Debug Assistant | `DebugAssistantService.java` | Analyzes stack traces with AI |
| Script analysis | `ReportService.java` | Analyzes uploaded scripts |
| Run log summary | `ReportService.java` | Summarizes script execution logs |
| Delta report | `DeltaReportService.java` | Analyzes scheduled delta report logs |
| What's New digest | `WhatsNewDigestService.java` | Personalizes What's New email intros |

### Config (application.properties)

```properties
# Portkey — set portkey.enabled=true to route all AI through Portkey
portkey.enabled=true
portkey.api-key=hyXJhCcEVh1Cpp+d5WsEoqfEk2oc
portkey.provider=@anthropic-eastus2
portkey.model=claude-sonnet-4-6

# Direct Anthropic fallback — still configured, used when portkey.enabled=false
ai.help.api-key=sk-ant-api03-hGZ...RgAA
ai.help.enabled=true
```

### To switch back to direct Anthropic

1. Re-enable the `agile_toolkit` key in the Anthropic Console
2. Set `portkey.enabled=false` in the external config
3. Restart the service

## Portkey Dashboard

- **Logs**: see every AI request, response, latency, tokens, cost
- **Analytics**: usage trends, error rates, model performance
- **API Keys**: manage your Portkey API key (the `plm` key)
- **AI Providers**: manage the Azure Foundry integration (add models, change settings)

Dashboard URL: https://app.portkey.ai
