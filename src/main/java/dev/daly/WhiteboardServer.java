package dev.daly;

import java.rmi.Remote;
import java.rmi.RemoteException;

// Interface defining the methods the server provides
public interface WhiteboardServer extends Remote {
    // Client calls this to register itself with the server
    void registerClient(ClientCallback client) throws RemoteException;

    // Client calls this when it's closing
    void unregisterClient(ClientCallback client) throws RemoteException;

    // Client calls this to send a new shape to the server for broadcasting
    // We include the sender's callback to avoid sending the shape back to them
    void publishShape(ShapeData shape, ClientCallback sender) throws RemoteException;

    // Client calls this to clear the entire whiteboard (including database)
    void clearWhiteboard() throws RemoteException;
}
