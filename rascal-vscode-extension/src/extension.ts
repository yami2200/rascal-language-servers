// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';
import * as path from 'path';
import * as net from 'net';
import * as cp from 'child_process';
import * as os from 'os';

import {LanguageClient, LanguageClientOptions, ServerOptions, StreamInfo, integer} from 'vscode-languageclient/node';
import { TextDocumentContentProvider } from 'vscode';
import { StringifyOptions } from 'node:querystring';

const deployMode = true;
const ALL_LANGUAGES_ID = 'parametric-rascalmpl';
let registeredFileExtensions:Array<String> = [];

let childProcess: cp.ChildProcessWithoutNullStreams;

class IDEServicesConfiguration {
    public port:integer;

    constructor (port:integer) {
        this.port = port;
    }
}

export function getRascalExtensionDeploymode() : boolean {
    return deployMode;
}

export function activate(context: vscode.ExtensionContext) {
    const parametricClient = activateParametricLanguageClient(context);
    const rascalClient = activateRascalLanguageClient(context, parametricClient);
    registerTerminalCommand(context, rascalClient);

    context.subscriptions.push(vscode.workspace.onDidOpenTextDocument(e => {
        const extension:string|undefined = e.fileName.split('.').pop();
        if (extension !== undefined && registeredFileExtensions.indexOf(extension) !== -1) {
            vscode.languages.setTextDocumentLanguage(e, ALL_LANGUAGES_ID);
        }
    }));

    const provider = new RascalTextDocumentContentProvider(rascalClient);

    vscode.workspace.registerTextDocumentContentProvider('project', provider);
    vscode.workspace.registerTextDocumentContentProvider('std', provider);
    vscode.workspace.registerTextDocumentContentProvider('lib', provider);

    console.log('LSP servers started (Rascal and Parametric)');
}

class RascalTextDocumentContentProvider implements TextDocumentContentProvider {
    onDidChange?: vscode.Event<vscode.Uri> | undefined;
    client:LanguageClient;

    constructor(client:LanguageClient) {
        this.client = client;
    }

    provideTextDocumentContent(uri: vscode.Uri, token: vscode.CancellationToken): vscode.ProviderResult<string> {
        const reply:Promise<LocationContent> = this.client.sendRequest("rascal/locationContents", { uri: uri.toString() });
        return reply.then(c => c.content);
    }
}

export function registerLanguage(context: vscode.ExtensionContext, client:LanguageClient, lang:LanguageParameter) {
    // first we load the new language into the parametric server
    client.sendRequest("rascal/sendRegisterLanguage", lang);

    if (registeredFileExtensions.indexOf(lang.extension) === -1) {
        registeredFileExtensions.push(lang.extension);
    }
}

export function activateRascalLanguageClient(context: vscode.ExtensionContext, parametricServer:LanguageClient):LanguageClient {
    return activateLanguageClient(context, 'rascalmpl', 'org.rascalmpl.vscode.lsp.rascal.RascalLanguageServer', 'Rascal MPL Language Server', 8888, parametricServer);
}

export function activateParametricLanguageClient(context: vscode.ExtensionContext) {
    return activateLanguageClient(context, 'parametric-rascalmpl', 'org.rascalmpl.vscode.lsp.parametric.ParametricLanguageServer', 'Language Parametric Rascal Language Server', 9999);
}

export function activateLanguageClient(context: vscode.ExtensionContext, language:string, main:string, title:string, devPort:integer, parametricServer?:LanguageClient) :LanguageClient {
    const serverOptions: ServerOptions = deployMode
        ? buildRascalServerOptions(context, main)
        : () => connectToRascalLanguageServerSocket(devPort) // we assume a server is running in debug mode
            .then((socket) => <StreamInfo> { writer: socket, reader: socket});

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: language }],
    };

    const client = new LanguageClient(language, title, serverOptions, clientOptions, true);

    client.onReady().then(() => {
        client.onNotification("rascal/showContent", (bp:BrowseParameter) => {
           showContentPanel(bp.uri);
        });

        if (parametricServer) {
            client.onNotification("rascal/receiveRegisterLanguage", (lang:LanguageParameter) => {
                registerLanguage(context, parametricServer, lang);
            });
        }
    });

    context.subscriptions.push(client.start());

    return client;
}


export function deactivate() {
    if (childProcess) {
        childProcess.kill('SIGKILL');
    }
}

function registerTerminalCommand(context: vscode.ExtensionContext, client:LanguageClient) {
    let disposable = vscode.commands.registerCommand('rascalmpl.createTerminal', () => {
        let editor = vscode.window.activeTextEditor;
        if (!editor) {
            return;
        }

        let document = editor.document;
        if (!document) {
            return;
        }

        let uri = document.uri;
        if (!uri) {
            return;
        }

        const reply:Promise<IDEServicesConfiguration> = client.sendRequest("rascal/supplyIDEServicesConfiguration");

        reply.then((cfg:IDEServicesConfiguration) => {
            let terminal = vscode.window.createTerminal({
                cwd: path.dirname(uri.fsPath),
                shellPath: getJavaExecutable(),
                shellArgs: [
                    '-cp' , buildJVMPath(context),
                    'org.rascalmpl.vscode.lsp.terminal.LSPTerminalREPL',
                    '--ideServicesPort',
                    '' + cfg.port
                ],
                name: 'Rascal Terminal',
            });

            context.subscriptions.push(disposable);
            terminal.show(false);
        });
    });
}

function buildJVMPath(context: vscode.ExtensionContext) :string {
    const jars = ['rascal-lsp.jar', 'rascal.jar', 'rascal-core.jar', 'typepal.jar'];
    return jars.map(j => context.asAbsolutePath(path.join('.', 'out', j))).join(path.delimiter);
}

function buildRascalServerOptions(context: vscode.ExtensionContext, main:string): ServerOptions {
    const classpath = buildJVMPath(context);
    return {
        command: 'java',
        args: ['-Dlog4j2.configurationFactory=org.rascalmpl.vscode.lsp.LogRedirectConfiguration', '-Dlog4j2.level=DEBUG',
            '-Drascal.lsp.deploy=true', '-Drascal.compilerClasspath=' + classpath,
            '-cp', classpath, main],
    };
}

function getJavaExecutable():string {
    const { JAVA_HOME } = process.env;

    const name = os.platform() === 'win32' ? 'java.exe' : 'java';
    return JAVA_HOME ? path.join(JAVA_HOME, 'bin', name) : name;
}

function connectToRascalLanguageServerSocket(port: number): Promise<net.Socket> {
    return new Promise((connected, failed) => {
        const maxTries = 20;
        const host = '127.0.0.1';
        let retryDelay = 0;
        const client = new net.Socket();
        var tries = 0;

        function retry(err?: Error) : net.Socket | void {
            if (tries <= maxTries) {
                setTimeout (() => {
                    tries++;
                    retryDelay = Math.min(2500, retryDelay + 250);
                    client.connect(port, host);
                }, retryDelay);
            }
            else {
                return failed("Connection retries exceeded" + (err ? (": " + err.message) : ""));
            }
        }

        client.setTimeout(1000);
        client.on('timeout', retry);
        client.on('error', retry);
        client.once('connect', () => {
            client.setTimeout(0);
            client.removeAllListeners();
            return connected(client);
        });

        return retry();
    });
}

let contentPanels : vscode.WebviewPanel[] = [];

function showContentPanel(url: string) : void {
    let oldPanel:vscode.WebviewPanel|undefined = contentPanels.find(p => (p as vscode.WebviewPanel).title === url);

    if (oldPanel) {
        oldPanel.dispose();
    }

    const panel = vscode.window.createWebviewPanel(
            "text/html",
            url,
            vscode.ViewColumn.One,
            {
                    enableScripts: true,
            }
    );

    contentPanels.push(panel);
    loadURLintoPanel(panel, url);

    panel.onDidDispose((e) => {
            contentPanels.splice(contentPanels.indexOf(panel), 1);
            // TODO: possibly clean up the server side while we are at it?
    });
}

function loadURLintoPanel(panel:vscode.WebviewPanel, url:string): void {
    panel.webview.html = `
            <!DOCTYPE html>
            <html lang="en">
            <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body>
            <iframe
                id="iframe-rascal-content"
                src="${url}"
                frameborder="0"
                sandbox="allow-scripts allow-forms allow-same-origin allow-pointer-lock allow-downloads allow-top-navigation"
                style="display: block; margin: 0px; overflow: hidden; position: absolute; width: 100%; height: 100%; visibility: visible;"
            >
            Loading ${url}...
            </iframe>
            </body>
            </html>`;
}

interface BrowseParameter {
    uri: string;
    mimetype: string;
    title:string;
}

interface URIParameter {
    uri:string;
}

interface LanguageParameter {
    pathConfig: string 		// rascal pathConfig constructor as a string
    name: string; 			// name of the language
    extension:string; 		// extension for files in this language
    mainModule: string; 	// main module to locate mainFunction in
    mainFunction: string; 	// main function which contributes the language implementation
}

interface LocationContent {
    content: string;
}
