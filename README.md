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

## Experimental native Repeater proof of concept

The current feature-branch build is an **M0 investigation candidate**, not an
accepted Repeater MVP release. The behavior and historical observations below
describe the imported PoC. Effective protocol/settings checks and native-model
editing remain unverified for this revision. See
[the M0 runbook](test-support/repeater/M0.md) for the isolated investigation
setup, explicitly enabled temporary probe, evidence gaps, and support matrix.

Three tools operate existing native HTTP Repeater tabs through Swing:

1. `get_repeater_tabs` returns generated `tabId` values and names. `kind` is
   `request` or `group`; group headers cannot be sent. Requests in collapsed groups
   can also be selected by ID. `groupPath` is `null` where Burp's flat group layout does not expose
   reliable membership.
2. `get_repeater_tab` takes `tabId` and returns the request, displayed response,
   actual target, and a `fingerprint`.
3. `send_repeater_tab` takes `tabId`, `expectedFingerprint`, `timeoutMs` (default
   10000), and `maxResponseLength` (default 20000). It selects that tab, checks that
   the request and target still match, clicks native Send, and returns a fresh
   exchange captured by Burp's HTTP handler. It does not use the separate HTTP
   send tools. The response includes its Burp `messageId` and `responseTruncated`.

For a new request, use `create_repeater_tab` or `create_repeater_tab_http2`, then
list tabs to obtain its ID (tab creation is asynchronous, so it may appear on a
subsequent list call). Names may be duplicated; sending always requires an
ID and the fingerprint returned by reading that tab. IDs expire when the server
restarts or a tab closes. Listing selects the Repeater tool;
reading and sending leave the requested tab selected.

Repeater access uses the existing project data approval setting, with its own
"Always allow Repeater access" checkbox. Sending also uses the existing target
approval check. Missing tabs, changed requests or targets, busy tabs, and
ambiguous controls produce an error before the Send click.

This is an unsupported UI integration, so Burp updates may break it. Keep HTTP
editors in Pretty or Raw view and use single-request Send mode. WebSocket tabs,
detached Repeater windows, full history retrieval, and request editing are outside
this proof of concept. Requests changed by session rules or other extensions may
not match the response observer. Redirect chains are not aggregated; the observer
returns the first matching exchange. Do not manually send identical requests
while an MCP send is running, because the API exposes no originating tab ID.

A timeout or cancellation after Send **does not stop the request in Burp**.
Inspect Burp before retrying. The tool makes no automatic retries.

Live validation used Burp Suite Community 2026.10.1 with a loopback test server:
existing and newly created tabs, duplicate names, expanded and collapsed groups,
closed-tab rejection, HTTP/1.1,
HTTP/2 over TLS/ALPN, response truncation, and a timeout without retries.
The tab's request-line text alone does not force HTTP/2 transport; Burp's native
protocol settings and negotiation apply. Cleartext HTTP/2 is outside this proof
of concept. A response that Burp has not finished rendering may be returned as
`null`; reading the tab again does not resend it.

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
