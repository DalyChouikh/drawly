package dev.daly;

import java.rmi.Remote;
import java.rmi.RemoteException;

// Interface defining the methods the server provides
public interface WhiteboardServer extends Remote {
    // Client calls this to register itself with the server for a specific room
    void registerClient(ClientCallback client, String roomName) throws RemoteException;

    // Client calls this when it's closing or leaving a room
    void unregisterClient(ClientCallback client, String roomName) throws RemoteException;

    // Client calls this to send a new shape to a specific room for broadcasting
    void publishShape(ShapeData shape, ClientCallback sender, String roomName) throws RemoteException;

    // Client calls this to clear a specific room's whiteboard
    void clearWhiteboard(String roomName) throws RemoteException;
}
