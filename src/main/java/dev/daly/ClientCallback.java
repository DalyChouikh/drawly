package dev.daly;

import java.rmi.Remote;
import java.rmi.RemoteException;

// Interface for the server to call back to the client
public interface ClientCallback extends Remote {
    // Method the server calls to tell the client to draw a shape
    void updateCanvas(ShapeData shape) throws RemoteException;
}
