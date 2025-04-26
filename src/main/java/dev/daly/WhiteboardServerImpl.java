package dev.daly;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*; // Import JDBC classes
import java.util.ArrayList; // Import ArrayList
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList; // Thread-safe list

public class WhiteboardServerImpl extends UnicastRemoteObject implements WhiteboardServer {

    // Use a thread-safe list to store client callbacks
    private final List<ClientCallback> clients;
    private Connection dbConnection; // Database connection

    // Database connection details (replace with your actual details or use config)
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/whiteboard_db"; // Adjust port/db name
    private static final String DB_USER = "postgres"; // Replace with your DB user
    private static final String DB_PASSWORD = "postgres123"; // Replace with your DB password

    protected WhiteboardServerImpl() throws RemoteException {
        super(); // Export this object
        clients = new CopyOnWriteArrayList<>();
        connectToDatabase();
    }

    private void connectToDatabase() {
        try {
            // Load the PostgreSQL driver (optional for modern JDBC)
            // Class.forName("org.postgresql.Driver");
            dbConnection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            System.out.println("Server: Database connection established.");
            // Optional: Create table if it doesn't exist (basic example)
            createShapesTableIfNotExists();
        } catch (SQLException e) {
            System.err.println("Server: Database connection failed: " + e.getMessage());
            // Handle connection failure (e.g., exit or run without DB)
            dbConnection = null; // Ensure connection is null if failed
        }
    }

    // Basic example - consider a more robust schema management approach
    // /*
    private void createShapesTableIfNotExists() {
        if (dbConnection == null)
            return;
        String createTableSQL = "CREATE TABLE IF NOT EXISTS shapes (" +
                "shape_id SERIAL PRIMARY KEY, " +
                "x_coord DOUBLE PRECISION NOT NULL, " +
                "y_coord DOUBLE PRECISION NOT NULL, " +
                "color_r INTEGER NOT NULL, " +
                "color_g INTEGER NOT NULL, " +
                "color_b INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);";
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute(createTableSQL);
            System.out.println("Server: Shapes table checked/created.");
        } catch (SQLException e) {
            System.err.println("Server: Error creating/checking shapes table: " + e.getMessage());
        }
    }
    // */

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

    private synchronized void saveShapeToDb(ShapeData shape) {
        if (dbConnection == null) {
            System.err.println("Server: Cannot save shape, no database connection.");
            return;
        }
        String sql = "INSERT INTO shapes (x_coord, y_coord, color_r, color_g, color_b, size) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setDouble(1, shape.x);
            pstmt.setDouble(2, shape.y);
            pstmt.setInt(3, shape.colorR);
            pstmt.setInt(4, shape.colorG);
            pstmt.setInt(5, shape.colorB);
            pstmt.setInt(6, shape.size);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Server: Error saving shape to database: " + e.getMessage());
        }
    }

    private synchronized List<ShapeData> loadShapesFromDb() {
        List<ShapeData> loadedShapes = new ArrayList<>();
        if (dbConnection == null) {
            System.err.println("Server: Cannot load shapes, no database connection.");
            return loadedShapes; // Return empty list
        }
        String sql = "SELECT x_coord, y_coord, color_r, color_g, color_b, size FROM shapes ORDER BY created_at ASC"; // Load
                                                                                                                     // in
                                                                                                                     // order
        try (Statement stmt = dbConnection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

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
            System.out.println("Server: Loaded " + loadedShapes.size() + " shapes from database.");
        } catch (SQLException e) {
            System.err.println("Server: Error loading shapes from database: " + e.getMessage());
        }
        return loadedShapes;
    }

    @Override
    public synchronized void registerClient(ClientCallback client) throws RemoteException {
        if (!clients.contains(client)) {
            clients.add(client);
            System.out.println("Server: Client registered. Total clients: " + clients.size());

            // Send existing shapes to the new client
            try {
                List<ShapeData> currentShapes = loadShapesFromDb();
                if (!currentShapes.isEmpty()) {
                    client.initializeCanvas(currentShapes); // Use the new callback method
                    System.out.println("Server: Sent " + currentShapes.size() + " existing shapes to new client.");
                }
            } catch (RemoteException e) {
                System.err
                        .println("Server: Error sending initial shapes to client. Removing client. " + e.getMessage());
                clients.remove(client); // Remove if initial send fails
                System.out.println(
                        "Server: Client removed due to initialization error. Total clients: " + clients.size());
            }
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
        System.out.println("Server: Received shape from a client.");
        // 1. Save shape to database *before* broadcasting
        saveShapeToDb(shape);

        // 2. Broadcast to other clients
        System.out.println("Server: Broadcasting shape...");
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
