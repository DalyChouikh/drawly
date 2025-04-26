package dev.daly;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map; // Import Map
import java.util.concurrent.ConcurrentHashMap; // Thread-safe map
import java.util.concurrent.CopyOnWriteArrayList;

public class WhiteboardServerImpl extends UnicastRemoteObject implements WhiteboardServer {

    // Store clients per room. Key: roomName, Value: List of clients in that room
    private final Map<String, CopyOnWriteArrayList<ClientCallback>> roomClients;
    private Connection dbConnection;
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/whiteboard_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres123";

    protected WhiteboardServerImpl() throws RemoteException {
        super();
        roomClients = new ConcurrentHashMap<>(); // Use a thread-safe map implementation
        connectToDatabase();
    }

    private void connectToDatabase() {
        try {
            dbConnection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            System.out.println("Server: Database connection established.");
            createShapesTableIfNotExists(); // Ensure table includes room_name
        } catch (SQLException e) {
            System.err.println("Server: Database connection failed: " + e.getMessage());
            dbConnection = null;
        }
    }

    // Updated to include room_name
    private void createShapesTableIfNotExists() {
        if (dbConnection == null)
            return;
        // Added room_name column
        String createTableSQL = "CREATE TABLE IF NOT EXISTS shapes (" +
                "shape_id SERIAL PRIMARY KEY, " +
                "room_name VARCHAR(255) NOT NULL, " + // Added room identifier
                "x_coord DOUBLE PRECISION NOT NULL, " +
                "y_coord DOUBLE PRECISION NOT NULL, " +
                "color_r INTEGER NOT NULL, " +
                "color_g INTEGER NOT NULL, " +
                "color_b INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);";
        // Add index separately to handle IF NOT EXISTS correctly for the index
        String createIndexSQL = "CREATE INDEX IF NOT EXISTS idx_shapes_room_name ON shapes (room_name);";

        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute(createTableSQL);
            System.out.println("Server: Shapes table checked/created.");
            stmt.execute(createIndexSQL); // Create index
            System.out.println("Server: Shapes room_name index checked/created.");
        } catch (SQLException e) {
            System.err.println("Server: Error creating/checking shapes table or index: " + e.getMessage());
        }
    }

    private void closeDatabaseConnection() {
        if (dbConnection != null) {
            try {
                dbConnection.close();
                System.out.println("Server: Database connection closed.");
            } catch (SQLException e) {
                System.err.println("Server: Error closing database connection: " + e.getMessage());
            }
        }
    }

    // Updated to include roomName
    private synchronized void saveShapeToDb(ShapeData shape, String roomName) {
        if (dbConnection == null) {
            System.err.println("Server: Cannot save shape, no database connection.");
            return;
        }
        // Added room_name to insert
        String sql = "INSERT INTO shapes (room_name, x_coord, y_coord, color_r, color_g, color_b, size) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName); // Set room name
            pstmt.setDouble(2, shape.x);
            pstmt.setDouble(3, shape.y);
            pstmt.setInt(4, shape.colorR);
            pstmt.setInt(5, shape.colorG);
            pstmt.setInt(6, shape.colorB);
            pstmt.setInt(7, shape.size);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Server: Error saving shape to database for room [" + roomName + "]: " + e.getMessage());
        }
    }

    // Updated to load shapes for a specific roomName
    private synchronized List<ShapeData> loadShapesFromDb(String roomName) {
        List<ShapeData> loadedShapes = new ArrayList<>();
        if (dbConnection == null) {
            System.err.println("Server: Cannot load shapes, no database connection.");
            return loadedShapes;
        }
        // Added WHERE clause for room_name
        String sql = "SELECT x_coord, y_coord, color_r, color_g, color_b, size FROM shapes WHERE room_name = ? ORDER BY created_at ASC";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName); // Set the room name parameter
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ShapeData shape = new ShapeData(
                            rs.getDouble("x_coord"),
                            rs.getDouble("y_coord"),
                            rs.getInt("color_r"),
                            rs.getInt("color_g"),
                            rs.getInt("color_b"),
                            rs.getInt("size"));
                    loadedShapes.add(shape);
                }
            }
            System.out.println(
                    "Server: Loaded " + loadedShapes.size() + " shapes from database for room [" + roomName + "].");
        } catch (SQLException e) {
            System.err.println(
                    "Server: Error loading shapes from database for room [" + roomName + "]: " + e.getMessage());
        }
        return loadedShapes;
    }

    // Updated to clear shapes for a specific roomName
    private synchronized void clearShapesFromDb(String roomName) {
        if (dbConnection == null) {
            System.err.println("Server: Cannot clear shapes, no database connection.");
            return;
        }
        // Added WHERE clause for room_name
        String sql = "DELETE FROM shapes WHERE room_name = ?";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName); // Set the room name parameter
            int deletedRows = pstmt.executeUpdate();
            System.out.println("Server: Cleared " + deletedRows + " shapes from database for room [" + roomName + "].");
        } catch (SQLException e) {
            System.err.println(
                    "Server: Error clearing shapes from database for room [" + roomName + "]: " + e.getMessage());
        }
    }

    @Override
    public synchronized void registerClient(ClientCallback client, String roomName) throws RemoteException {
        // Get or create the list for the room
        CopyOnWriteArrayList<ClientCallback> clientsInRoom = roomClients.computeIfAbsent(roomName,
                k -> new CopyOnWriteArrayList<>());

        if (!clientsInRoom.contains(client)) {
            clientsInRoom.add(client);
            System.out.println("Server: Client registered for room [" + roomName + "]. Total clients in room: "
                    + clientsInRoom.size());

            // Send existing shapes for *this room* to the new client
            try {
                List<ShapeData> currentShapes = loadShapesFromDb(roomName);
                client.initializeCanvas(currentShapes); // Send initial state for the room
                System.out.println("Server: Sent " + currentShapes.size() + " existing shapes to new client in room ["
                        + roomName + "].");

            } catch (RemoteException e) {
                System.err.println("Server: Error sending initial shapes to client in room [" + roomName
                        + "]. Removing client. " + e.getMessage());
                clientsInRoom.remove(client); // Remove if initial send fails
                if (clientsInRoom.isEmpty()) {
                    roomClients.remove(roomName); // Clean up empty room entry
                }
                System.out.println("Server: Client removed due to initialization error. Total clients in room ["
                        + roomName + "]: " + (clientsInRoom.isEmpty() ? 0 : clientsInRoom.size()));
            }
        } else {
            System.out.println("Server: Client already registered in room [" + roomName + "].");
        }
    }

    @Override
    public synchronized void unregisterClient(ClientCallback client, String roomName) throws RemoteException {
        CopyOnWriteArrayList<ClientCallback> clientsInRoom = roomClients.get(roomName);
        if (clientsInRoom != null) {
            if (clientsInRoom.remove(client)) {
                System.out.println("Server: Client unregistered from room [" + roomName + "]. Total clients in room: "
                        + clientsInRoom.size());
                if (clientsInRoom.isEmpty()) {
                    roomClients.remove(roomName); // Clean up empty room entry
                    System.out.println("Server: Room [" + roomName + "] is now empty and removed.");
                }
            } else {
                System.out.println("Server: Client not found in room [" + roomName + "] for unregistration.");
            }
        } else {
            System.out.println("Server: Room [" + roomName + "] not found for unregistration.");
        }
    }

    @Override
    public void publishShape(ShapeData shape, ClientCallback sender, String roomName) throws RemoteException {
        System.out.println("Server: Received shape from a client for room [" + roomName + "].");
        // 1. Save shape to database with room name
        saveShapeToDb(shape, roomName);

        // 2. Broadcast only to clients in the same room
        CopyOnWriteArrayList<ClientCallback> clientsInRoom = roomClients.get(roomName);
        if (clientsInRoom != null) {
            System.out.println(
                    "Server: Broadcasting shape to room [" + roomName + "] (" + clientsInRoom.size() + " clients)...");
            for (ClientCallback client : clientsInRoom) {
                if (!client.equals(sender)) { // Don't send back to sender
                    try {
                        client.updateCanvas(shape);
                    } catch (RemoteException e) {
                        System.err.println("Server: Error calling client callback in room [" + roomName
                                + "]. Removing client. " + e.getMessage());
                        // Remove the problematic client directly from this room's list
                        clientsInRoom.remove(client);
                        System.out.println("Server: Client removed due to error. Total clients in room [" + roomName
                                + "]: " + clientsInRoom.size());
                        if (clientsInRoom.isEmpty()) {
                            roomClients.remove(roomName); // Clean up empty room entry
                            System.out.println("Server: Room [" + roomName + "] is now empty and removed.");
                        }
                    }
                }
            }
        } else {
            System.out.println("Server: No clients found in room [" + roomName + "] to broadcast shape.");
        }
    }

    @Override
    public synchronized void clearWhiteboard(String roomName) throws RemoteException {
        System.out.println("Server: Received clear request for room [" + roomName + "].");
        // 1. Clear database for the specific room
        clearShapesFromDb(roomName);

        // 2. Notify all clients *in that room* to clear their canvas
        CopyOnWriteArrayList<ClientCallback> clientsInRoom = roomClients.get(roomName);
        if (clientsInRoom != null) {
            System.out.println("Server: Broadcasting clear command to room [" + roomName + "] (" + clientsInRoom.size()
                    + " clients)...");
            List<ClientCallback> clientsToRemove = new ArrayList<>();
            for (ClientCallback client : clientsInRoom) {
                try {
                    client.clearCanvas();
                } catch (RemoteException e) {
                    System.err.println("Server: Error calling clearCanvas on client in room [" + roomName
                            + "]. Removing client. " + e.getMessage());
                    clientsToRemove.add(client);
                }
            }
            // Remove clients that failed during broadcast
            if (!clientsToRemove.isEmpty()) {
                clientsInRoom.removeAll(clientsToRemove);
                System.out.println("Server: Removed " + clientsToRemove.size() + " clients from room [" + roomName
                        + "] due to clearCanvas error. Total clients in room: " + clientsInRoom.size());
                if (clientsInRoom.isEmpty()) {
                    roomClients.remove(roomName); // Clean up empty room entry
                    System.out.println("Server: Room [" + roomName + "] is now empty and removed.");
                }
            }
        } else {
            System.out.println("Server: No clients found in room [" + roomName + "] to broadcast clear command.");
        }
    }

    public static void main(String[] args) {
        WhiteboardServerImpl server = null;
        try {
            // 1. Create the server implementation
            server = new WhiteboardServerImpl();
            final WhiteboardServerImpl finalServer = server; // For use in shutdown hook

            // Add shutdown hook to close DB connection
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Server: Shutdown hook triggered.");
                if (finalServer != null) {
                    finalServer.closeDatabaseConnection();
                }
            }));

            // 2. Start the RMI Registry
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
            // Ensure DB connection is closed if server creation fails partially
            if (server != null) {
                server.closeDatabaseConnection();
            }
        }
    }
}
