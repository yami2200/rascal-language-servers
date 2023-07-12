package org.rascalmpl.vscode.lsp.dap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.vscode.lsp.terminal.TerminalIDEClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class DebugSocketServer {

    private ServerSocket serverSocket;
    private Socket clientSocket;

    public void startSocketServer(Evaluator evaluator, TerminalIDEClient terminal){
        try{
            serverSocket = new ServerSocket(0);
            registerDebugServerPort(terminal);

            Thread t = new Thread(() -> {
                while(true){
                    try {
                        Socket newClient = serverSocket.accept();
                        if(clientSocket == null || clientSocket.isClosed()){
                            clientSocket = newClient;
                            RascalDebugAdapterLauncher.start(evaluator, clientSocket, this);
                        } else {
                            newClient.close();
                        }
                    } catch (IOException e) {
                        final Logger logger = LogManager.getLogger(DebugSocketServer.class);
                        logger.error(e.getMessage(), e);
                    }
                }
            });
            t.setDaemon(true);
            t.start();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void registerDebugServerPort(TerminalIDEClient terminal){
        terminal.registerDebugServerPort((int) ProcessHandle.current().pid(), getPort());
    }

    public boolean isClientConnected(){
        return clientSocket != null && !clientSocket.isClosed();
    }

    public int getPort(){
        return serverSocket.getLocalPort();
    }

    public void disconnectClient(){
        clientSocket = null;
    }
}
