package dev.daly;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List; // Import List

// Interface for the server to call back to the client
public interface ClientCallback extends Remote {
    // Method the server calls to tell the client to draw a new shape
    void updateCanvas(ShapeData shape) throws RemoteException;

    // Method the server calls to send the initial state of the canvas
    void initializeCanvas(List<ShapeData> initialShapes) throws RemoteException;
}
