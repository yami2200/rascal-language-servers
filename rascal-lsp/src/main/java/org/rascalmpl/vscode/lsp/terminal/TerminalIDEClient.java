package org.rascalmpl.vscode.lsp.terminal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.rascalmpl.ideservices.IDEServices;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.BrowseParameter;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.EditParameter;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.LanguageParameter;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.SourceLocationParameter;

import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.IWithKeywordParameters;

/**
 * This class provides IDE services to a Rascal REPL by
 * remote procedure invocation on a server which is embedded
 * into the LSP Rascal language server. That server forwards
 * the request to the Rascal IDE client (@see TerminalIDEServer)
 */
public class TerminalIDEClient implements IDEServices {
    private final ITerminalIDEServer server;

    public TerminalIDEClient(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
        Launcher<ITerminalIDEServer> launch = new Launcher.Builder<ITerminalIDEServer>()
            .setRemoteInterface(ITerminalIDEServer.class)
            .setLocalService(this)
            .setInput(socket.getInputStream())
            .setOutput(socket.getOutputStream())
            .create();
        launch.startListening();
        server = launch.getRemoteProxy();
    }

    @Override
    public void browse(URI uri) {
        server.browse(new BrowseParameter(uri.toString()));
    }

    @Override
    public void edit(ISourceLocation path) {
       server.edit(new EditParameter(path.getPath()));
    }

    @Override
    public ISourceLocation resolveProjectLocation(ISourceLocation input) {
        try {
            return server.resolveProjectLocation(new SourceLocationParameter(input))
                .get()
                .getLocation();
        } catch (InterruptedException | ExecutionException e) {
            return input;
        }
    }

    @Override
    public void registerLanguage(IConstructor language) {
        server.receiveRegisterLanguage(
            new LanguageParameter(
                language.get(0).toString(), 
                ((IString) language.get(1)).getValue(), 
                ((IString) language.get(2)).getValue(), 
                ((IString) language.get(3)).getValue(), 
                ((IString) language.get(4)).getValue()
            )
        );
    }

    @Override
    public void startJob(String name) {
        // TODO Auto-generated method stub
    }

    @Override
    public void startJob(String name, int totalWork) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void startJob(String name, int workShare, int totalWork) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void event(String name) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void event(String name, int inc) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void event(int inc) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public int endJob(boolean succeeded) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean isCanceled() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void todo(int work) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void warning(String message, ISourceLocation src) {
        // TODO Auto-generated method stub
        
    }
}