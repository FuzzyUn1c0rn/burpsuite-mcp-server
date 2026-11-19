# Burp Suite MCP Server Extension

## Overview

Integrate Burp Suite with AI Clients using the Model Context Protocol (MCP).

For more information about the protocol visit: [modelcontextprotocol.io](https://modelcontextprotocol.io/)

## Features

- Connect Burp Suite to AI clients through MCP
- Automatic installation for Claude Desktop
- Serves both Streamable HTTP (`/mcp`) and legacy SSE (`/`) transports
- Comes with packaged Stdio MCP proxy server

## Usage

- Install the extension in Burp Suite
- Configure your Burp MCP server in the extension settings
- Configure your MCP client to use the Burp SSE MCP server or stdio proxy
- Interact with Burp through your client!

## Installation

### Prerequisites

Ensure that the following prerequisites are met before building and installing the extension:

1. **Java**: Java must be installed and available in your system's PATH. You can verify this by running `java --version` in your terminal.
2. **jar Command**: The `jar` command must be executable and available in your system's PATH. You can verify this by running `jar --version` in your terminal. This is required for building and installing the extension.

### Building the Extension

1. **Clone the Repository**: Obtain the source code for the MCP Server Extension.
   ```
   git clone https://github.com/PortSwigger/mcp-server.git
   ```

2. **Navigate to the Project Directory**: Move into the project's root directory.
   ```
   cd mcp-server
   ```

3. **Build the JAR File**: Use Gradle to build the extension.
   ```
   ./gradlew embedProxyJar
   ```

   This command compiles the source code and packages it into a JAR file located in `build/libs/burp-mcp-all.jar`.

### Loading the Extension into Burp Suite

1. **Open Burp Suite**: Launch your Burp Suite application.
2. **Access the Extensions Tab**: Navigate to the `Extensions` tab.
3. **Add the Extension**:
    - Click on `Add`.
    - Set `Extension Type` to `Java`.
    - Click `Select file ...` and choose the JAR file built in the previous step.
    - Click `Next` to load the extension.

Upon successful loading, the MCP Server Extension will be active within Burp Suite.

## Configuration

### Configuring the Extension
Configuration for the extension is done through the Burp Suite UI in the `MCP` tab.
- **Toggle the MCP Server**: The `Enabled` checkbox controls whether the MCP server is active.
- **Enable config editing**: The `Enable tools that can edit your config` checkbox allows the MCP server to expose tools which can edit Burp configuration files.
- **Advanced options**: You can configure the port and host for the MCP server. By default, it listens on `http://127.0.0.1:9876`.

### Claude Desktop Client

To fully utilize the MCP Server Extension with Claude, you need to configure your Claude client settings appropriately.
The extension has an installer which will automatically configure the client settings for you.

1. Currently, Claude Desktop only support STDIO MCP Servers
   for the service it needs.
   This approach isn't ideal for desktop apps like Burp, so instead, Claude will start a proxy server that points to the
   Burp instance,  
   which hosts a web server at a known port (`localhost:9876`).

2. **Configure Claude to use the Burp MCP server**  
   You can do this in one of two ways:

    - **Option 1: Run the installer from the extension**
      This will add the Burp MCP server to the Claude Desktop config.

    - **Option 2: Manually edit the config file**  
      Open the file located at `~/Library/Application Support/Claude/claude_desktop_config.json`,
      and replace or update it with the following:
      ```json
      {
        "mcpServers": {
          "burp": {
            "command": "<path to Java executable packaged with Burp>",
            "args": [
                "-jar",
                "/path/to/mcp/proxy/jar/mcp-proxy-all.jar",
                "--sse-url",
                "<your Burp MCP server URL configured in the extension>"
            ]
          }
        }
      }
      ```

3. **Restart Claude Desktop** - assuming Burp is running with the extension loaded.

### Codex

Codex connects to the extension's Streamable HTTP endpoint directly; no proxy jar or Java path is needed.

#### Plugin (recommended)

The Burp Suite MCP Server plugin sets up the connection and adds a skill that teaches Codex how to use the tools:
which to reach for, how Burp's approval prompts behave and what to do when a call hangs.

```bash
codex plugin marketplace add PortSwigger/mcp-server
codex plugin add burp-suite-mcp-server@portswigger
```

The plugin expects the extension on `http://127.0.0.1:9876`. If you changed the host or port, or want Codex to run
the tools without asking (needed for non-interactive `codex exec` runs), override the server in
`~/.codex/config.toml`. Give the full entry, including `url`:

```toml
[mcp_servers.burp]
url = "http://127.0.0.1:9876/mcp"
default_tools_approval_mode = "approve"
```

#### Without the plugin

```bash
codex mcp add burp --url http://127.0.0.1:9876/mcp
```

The skill lives in `plugins/burp-suite-mcp-server/skills/burp-mcp`; copy that folder into `~/.codex/skills/` to use
it without the plugin.

#### Approvals

By default the extension asks for approval in Burp before sending HTTP requests or reading proxy history, so keep an
eye on Burp while Codex works, or use the auto-approve options in the `MCP` tab.

## Native Repeater drafts

Enable native draft listing and reads by starting Burp with the JVM property
`-Dnet.portswigger.mcp.repeater.drafts=true`. The initial adapter targets
Burp Community 2026.11.2 on Windows with main-window Repeater tabs.
List the drafts with `get_repeater_tabs` and read one by its returned ID
with `get_repeater_tab`. Duplicate names are allowed. Listing can select
the Repeater tool. Reading selects the requested draft and leaves it
selected. Select the draft's **Hex** request view for an authoritative
model-backed read. Other views may be refused.

The read reports the native model's HTTP version, destination and whole-draft
fingerprint. Configured protocol is unverified. Set `includeRequest: false`
to omit the request document while keeping its fingerprint. The bounded
response preview, when available, represents the currently displayed UI and
may belong to an earlier draft. Set `includeResponse: false` or
`maxResponseLength: 0` to omit preview text. Repeater data permission still
applies. IDs remain valid across reconnects to the same service and expire
after a project or service change.

`create_repeater_tab` and `create_repeater_tab_http2` open drafts using the
existing creation contract. Draft confirmation, cloning and editing are
outside the current candidate. Use only the
[isolated M0 runbook](test-support/repeater/M0.md) for the temporary probe.
The candidate contains no Repeater Send or send-status tool.

## Manual installations
If you want to install the MCP server manually you can either use the extension's SSE server directly or the packaged
Stdio proxy server.

### Streamable HTTP MCP Server
Clients that support the Streamable HTTP transport (e.g. Codex) can use the `/mcp` path on the configured server URL:
```
http://127.0.0.1:9876/mcp
```

### SSE MCP Server
To use the legacy SSE server directly, provide the configured server URL to your MCP client:
```
http://127.0.0.1:9876
```

### Stdio MCP Proxy Server
The source code for the proxy server can be found here: [MCP Proxy Server](https://github.com/PortSwigger/mcp-proxy)

In order to support MCP Clients which only support Stdio MCP Servers, the extension comes packaged with a proxy server for
passing requests to the SSE MCP server extension.

If you want to use the Stdio proxy server you can use the extension's installer option to extract the proxy server jar.
Once you have the jar you can add the following command and args to your client configuration:
```
/path/to/packaged/burp/java -jar /path/to/proxy/jar/mcp-proxy-all.jar --sse-url http://127.0.0.1:9876
```

If you modify the proxy source, rebuild and copy it into this project before packaging the extension:
```bash
# From mcp-proxy
./gradlew shadowJar
cp build/libs/mcp-proxy-all.jar /path/to/mcp-server/libs/mcp-proxy-all.jar

# From mcp-server
./gradlew embedProxyJar
```

### Creating / modifying tools

Tools are defined in `src/main/kotlin/net/portswigger/mcp/tools/Tools.kt`. To define new tools, create a new serializable
data class with the required parameters which will come from the LLM.

The tool name is auto-derived from its parameters data class. A description is also needed for the LLM. You can return
a string or a `List<ContentBlock>` to provide data back to the LLM.

Extend the Paginated interface to add auto-pagination support.
