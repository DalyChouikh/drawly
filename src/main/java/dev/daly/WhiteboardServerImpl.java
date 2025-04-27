package dev.daly;

import java.awt.Color;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        roomClients = new ConcurrentHashMap<>();
        connectToDatabase();
    }

    private void connectToDatabase() {
        try {
            //Class.forName("org.postgresql.Driver");
            dbConnection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            System.out.println("Server: Database connection established.");
            createShapesTableIfNotExists();
        } catch (SQLException e) {
            System.err.println("Server: Database connection failed: " + e.getMessage());
            dbConnection = null;
        }
    }

    private void createShapesTableIfNotExists() {
        if (dbConnection == null)
            return;
        String createTableSQL = "CREATE TABLE IF NOT EXISTS shapes (" +
                "shape_id SERIAL PRIMARY KEY, " +
                "room_name VARCHAR(255) NOT NULL, " +
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
            stmt.execute(createIndexSQL);
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

    private synchronized void saveShapeToDb(ShapeData shape, String roomName) {
        if (dbConnection == null) {
            System.err.println("Server: Cannot save shape, no database connection.");
            return;
        }
        String sql = "INSERT INTO shapes (room_name, x_coord, y_coord, color_r, color_g, color_b, size) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName);
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

    private synchronized List<ShapeData> loadShapesFromDb(String roomName) {
        List<ShapeData> loadedShapes = new ArrayList<>();
        if (dbConnection == null) {
            System.err.println("Server: Cannot load shapes, no database connection.");
            return loadedShapes;
        }
        String sql = "SELECT x_coord, y_coord, color_r, color_g, color_b, size FROM shapes WHERE room_name = ? ORDER BY created_at ASC";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName);
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

    private synchronized void clearShapesFromDb(String roomName) {
        if (dbConnection == null) {
            System.err.println("Server: Cannot clear shapes, no database connection.");
            return;
        }
        String sql = "DELETE FROM shapes WHERE room_name = ?";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName);
            int deletedRows = pstmt.executeUpdate();
            System.out.println("Server: Cleared " + deletedRows + " shapes from database for room [" + roomName + "].");
        } catch (SQLException e) {
            System.err.println(
                    "Server: Error clearing shapes from database for room [" + roomName + "]: " + e.getMessage());
        }
    }


    // Helper method to check if a room exists in the database
    private synchronized boolean doesRoomExistInDb(String roomName) {
        if (dbConnection == null) {
            System.err.println("Server: Cannot check room existence, no database connection.");
            return false;
        }
        String sql = "SELECT COUNT(*) FROM shapes WHERE room_name = ?";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, roomName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("Server: Error checking existence of room [" + roomName + "] in database: " + e.getMessage());
        }
        return false;
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

            // Check if room exists in DB, if not, add a dummy shape
            if (!doesRoomExistInDb(roomName)) {
                System.out.println("Server: Room [" + roomName + "] not found in DB. Adding initial dummy shape.");
                // Create a dummy shape (white, small, at origin)
                ShapeData dummyShape = new ShapeData(0, 0, Color.WHITE, 1);
                saveShapeToDb(dummyShape, roomName);
            }

            // Send existing shapes (including the dummy one if just added)
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
                    roomClients.remove(roomName); // Clean up empty room entry if no other clients are left
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
                // Only remove the room from the map if no clients are left *in memory*
                if (clientsInRoom.isEmpty()) {
                    roomClients.remove(roomName);
                    System.out.println(
                            "Server: Room [" + roomName + "] is now empty in memory and removed from active map.");
                }
            } else {
                System.out.println("Server: Client not found in room [" + roomName + "] for unregistration.");
            }
        } else {
            System.out.println("Server: Room [" + roomName + "] not found in active map for unregistration.");
        }
    }

    @Override
    public void publishShape(ShapeData shape, ClientCallback sender, String roomName) throws RemoteException {
        System.out.println("Server: Received shape from a client for room [" + roomName + "].");
        // Save shape to database with room name
        saveShapeToDb(shape, roomName);

        // Broadcast only to clients currently connected to the same room
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
                            roomClients.remove(roomName); // Clean up empty room entry if no other clients are left
                            System.out.println("Server: Room [" + roomName
                                    + "] is now empty in memory and removed from active map.");
                        }
                    }
                }
            }
        } else {
            System.out.println("Server: No active clients found in room [" + roomName + "] to broadcast shape.");
        }
    }

    @Override
    public synchronized void clearWhiteboard(String roomName) throws RemoteException {
        System.out.println("Server: Received clear request for room [" + roomName + "].");
        // Clear database for the specific room
        clearShapesFromDb(roomName);

        // Notify all clients *currently connected to that room* to clear their canvas
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
                    roomClients.remove(roomName); // Clean up empty room entry if no other clients are left
                    System.out.println(
                            "Server: Room [" + roomName + "] is now empty in memory and removed from active map.");
                }
            }
        } else {
            System.out
                    .println("Server: No active clients found in room [" + roomName + "] to broadcast clear command.");
        }
    }

    @Override
    public List<String> getRoomList() throws RemoteException {
        System.out.println("Server: getRoomList called. Querying database...");
        List<String> dbRooms = new ArrayList<>();
        if (dbConnection == null) {
            System.err.println("Server: Cannot get room list, no database connection.");
            // throw new RemoteException("Database connection unavailable");
            return dbRooms; // Return empty list if DB connection failed
        }

        // Query distinct room names from the shapes table
        String sql = "SELECT DISTINCT room_name FROM shapes ORDER BY room_name ASC";
        try (Statement stmt = dbConnection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                dbRooms.add(rs.getString("room_name"));
            }
            System.out.println("Server: Found " + dbRooms.size() + " distinct rooms in database.");
        } catch (SQLException e) {
            System.err.println("Server: Error querying distinct room names from database: " + e.getMessage());
            // throw new RemoteException("Error accessing room data", e);
            return Collections.emptyList(); // Return empty list on DB error
        }
        return dbRooms;
    }

    public static void main(String[] args) {
        WhiteboardServerImpl server = null;
        try {
            // Create the server implementation
            server = new WhiteboardServerImpl();
            final WhiteboardServerImpl finalServer = server; // For use in shutdown hook

            // Add shutdown hook to close DB connection
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Server: Shutdown hook triggered.");
                if (finalServer != null) {
                    finalServer.closeDatabaseConnection();
                }
            }));

            // Start the RMI Registry
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(1099);
                System.out.println("Server: RMI Registry created on port 1099.");
            } catch (RemoteException e) {
                System.out.println("Server: RMI Registry might already be running. Trying to get existing registry...");
                registry = LocateRegistry.getRegistry(1099);
            }

            // Bind the server implementation to the registry
            // Use Naming.rebind to overwrite any existing binding
            Naming.rebind("rmi://localhost/WhiteboardService", server);

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
