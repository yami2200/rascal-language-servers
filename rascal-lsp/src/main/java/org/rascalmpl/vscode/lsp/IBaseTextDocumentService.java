package org.rascalmpl.vscode.lsp;

import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.LanguageParameter;

public interface IBaseTextDocumentService extends TextDocumentService {
    void initializeServerCapabilities(ServerCapabilities result);
    void shutdown();
    void connect(LanguageClient client);
    void registerLanguage(LanguageParameter lang);
}
