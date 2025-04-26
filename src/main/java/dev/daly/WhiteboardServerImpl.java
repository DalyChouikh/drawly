package dev.daly;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList; // Thread-safe list

public class WhiteboardServerImpl extends UnicastRemoteObject implements WhiteboardServer {

    // Use a thread-safe list to store client callbacks
    private final List<ClientCallback> clients;

    protected WhiteboardServerImpl() throws RemoteException {
        super(); // Export this object
        clients = new CopyOnWriteArrayList<>();
    }

    @Override
    public synchronized void registerClient(ClientCallback client) throws RemoteException {
        if (!clients.contains(client)) {
            clients.add(client);
            System.out.println("Server: Client registered. Total clients: " + clients.size());
        }
    }

    @Override
    public synchronized void unregisterClient(ClientCallback client) throws RemoteException {
        if (clients.remove(client)) {
            System.out.println("Server: Client unregistered. Total clients: " + clients.size());
        } else {
            System.out.println("Server: Client not found for unregistration.");
        }
    }

    @Override
    public void publishShape(ShapeData shape, ClientCallback sender) throws RemoteException {
        System.out.println("Server: Received shape from a client. Broadcasting...");
        // Iterate over a snapshot of the list (CopyOnWriteArrayList is good for this)
        for (ClientCallback client : clients) {
            // Don't send the shape back to the client who originally sent it
            if (!client.equals(sender)) {
                try {
                    // Call the remote method on the client
                    client.updateCanvas(shape);
                } catch (RemoteException e) {
                    // Problem communicating with a client (e.g., client crashed)
                    // Remove the problematic client
                    System.err.println("Server: Error calling client callback. Removing client. " + e.getMessage());
                    clients.remove(client);
                    System.out.println("Server: Client removed due to error. Total clients: " + clients.size());
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            // 1. Create the server implementation
            WhiteboardServerImpl server = new WhiteboardServerImpl();

            // 2. Start the RMI Registry (usually on port 1099)
            // Try to create a registry, or get it if it already exists
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(1099);
                System.out.println("Server: RMI Registry created on port 1099.");
            } catch (RemoteException e) {
                System.out.println("Server: RMI Registry might already be running. Trying to get existing registry...");
                registry = LocateRegistry.getRegistry(1099);
            }


            // 3. Bind the server implementation to the registry
            // Use Naming.rebind to overwrite any existing binding
            Naming.rebind("//localhost/WhiteboardService", server); // Use localhost or specific IP/hostname

            System.out.println("Server: WhiteboardService bound in registry.");
            System.out.println("Server: Ready.");

        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}
