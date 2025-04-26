package dev.daly;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class WhiteboardClient implements ClientCallback {

    private static final String SERVER_URL = "//localhost/WhiteboardService";
    private JFrame frame;
    private DrawingPanel drawingPanel;
    private WhiteboardServer serverStub;
    private ClientCallback clientCallbackStub;
    private final List<ShapeData> shapes = new CopyOnWriteArrayList<>();
    private Color currentColor = Color.BLACK;
    private int currentSize = 4;
    private JLabel colorPreview;
    private String roomName; // Added to store the current room name

    public WhiteboardClient() {
        // Prompt for Room Name *before* creating the main frame
        this.roomName = promptForRoomName();
        if (this.roomName == null || this.roomName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(null, "A valid room name is required to start.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1); // Exit if no valid room name is provided
        }

        // Initialize GUI components
        frame = new JFrame("Drawly - Room: " + roomName); // Show room name in title
        // ... rest of GUI setup ...
        drawingPanel = new DrawingPanel();
        drawingPanel.setPreferredSize(new Dimension(800, 600));
        drawingPanel.setBackground(Color.WHITE);

        // --- Control Panel ---
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton colorButton = new JButton("Choose Color");
        colorPreview = new JLabel("  "); // Small label to show color
        colorPreview.setOpaque(true);
        colorPreview.setBackground(currentColor);
        colorPreview.setPreferredSize(new Dimension(20, 20));
        colorPreview.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        JSlider sizeSlider = new JSlider(JSlider.HORIZONTAL, 1, 50, currentSize);
        JLabel sizeLabel = new JLabel("Size: " + currentSize);
        sizeSlider.setPreferredSize(new Dimension(150, sizeSlider.getPreferredSize().height));

        JButton clearButton = new JButton("Clear Room"); // Changed button text

        // Color Chooser Action
        colorButton.addActionListener(e -> {
            Color chosenColor = JColorChooser.showDialog(frame, "Select Drawing Color", currentColor);
            if (chosenColor != null) {
                currentColor = chosenColor;
                colorPreview.setBackground(currentColor); // Update preview
            }
        });

        // Size Slider Action
        sizeSlider.addChangeListener(e -> {
            currentSize = sizeSlider.getValue();
            sizeLabel.setText("Size: " + currentSize);
        });

        // Clear Button Action - Pass roomName
        clearButton.addActionListener(e -> {
            if (serverStub != null) {
                int confirmation = JOptionPane.showConfirmDialog(frame,
                        "Are you sure you want to clear the whiteboard for room '" + roomName + "'?", // Updated message
                        "Confirm Clear Room", // Updated title
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);

                if (confirmation == JOptionPane.YES_OPTION) {
                    new Thread(() -> {
                        try {
                            System.out.println("Client: Sending clear request for room [" + roomName + "]...");
                            serverStub.clearWhiteboard(roomName); // Pass roomName
                            System.out.println("Client: Clear request sent for room [" + roomName + "].");
                        } catch (RemoteException ex) {
                            System.err.println("Client: Error sending clear request for room [" + roomName + "]: "
                                    + ex.getMessage());
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                                    "Error sending clear command: " + ex.getMessage(), "Communication Error",
                                    JOptionPane.WARNING_MESSAGE));
                        }
                    }).start();
                }
            }
        });

        controlPanel.add(colorButton);
        controlPanel.add(colorPreview);
        controlPanel.add(new JLabel("   ")); // Spacer
        controlPanel.add(sizeSlider);
        controlPanel.add(sizeLabel);
        controlPanel.add(new JLabel("   ")); // Spacer
        controlPanel.add(clearButton);
        // --- End Control Panel ---

        // ... rest of constructor ...
        DrawingMouseListener mouseListener = new DrawingMouseListener();
        drawingPanel.addMouseListener(mouseListener);
        drawingPanel.addMouseMotionListener(mouseListener);

        frame.getContentPane().add(controlPanel, BorderLayout.NORTH);
        frame.getContentPane().add(drawingPanel, BorderLayout.CENTER);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Connect to RMI server (now happens after getting room name)
        connectToServer();
    }

    // Helper method to prompt for room name
    private String promptForRoomName() {
        return JOptionPane.showInputDialog(
                null, // Parent component
                "Enter Room Name:", // Message
                "Join Room", // Title
                JOptionPane.PLAIN_MESSAGE // Message type
        );
    }

    // Modified to pass roomName during registration
    private void connectToServer() {
        if (roomName == null || roomName.trim().isEmpty()) {
            System.err.println("Client: Cannot connect without a room name.");
            // Optionally show an error message and exit more gracefully
            JOptionPane.showMessageDialog(null, "Connection cancelled: No room name provided.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        try {
            clientCallbackStub = (ClientCallback) UnicastRemoteObject.exportObject(this, 0);
            serverStub = (WhiteboardServer) Naming.lookup(SERVER_URL);
            // Register with the specific room name
            serverStub.registerClient(clientCallbackStub, roomName);
            System.out.println("Client: Connected to server and registered for room [" + roomName + "].");

        } catch (Exception e) {
            System.err.println("Client connection error: " + e.toString());
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame,
                    "Error connecting to server for room '" + roomName + "': " + e.getMessage(), "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
            shutdown();
        }
    }

    // ... ClientCallback Implementation (updateCanvas, initializeCanvas,
    // clearCanvas) - No changes needed ...
    @Override
    public void updateCanvas(ShapeData shape) throws RemoteException {
        // This method is called by an RMI thread when a *new* shape is broadcasted.
        SwingUtilities.invokeLater(() -> {
            shapes.add(shape); // Add the single new shape
            drawingPanel.repaint(); // Request redraw
        });
    }

    @Override
    public void initializeCanvas(List<ShapeData> initialShapes) throws RemoteException {
        // This method is called by the server *once* after registration.
        SwingUtilities.invokeLater(() -> {
            System.out.println("Client: Receiving initial shapes: " + initialShapes.size());
            shapes.clear(); // Clear any existing local shapes
            shapes.addAll(initialShapes); // Add all shapes received from the server
            drawingPanel.repaint(); // Redraw the canvas with the initial state
        });
    }

    @Override
    public void clearCanvas() throws RemoteException {
        // This method is called by the server via RMI to clear the canvas.
        SwingUtilities.invokeLater(() -> {
            System.out.println("Client: Received clear command. Clearing canvas.");
            shapes.clear(); // Clear the local list of shapes
            drawingPanel.repaint(); // Request redraw of the now empty panel
        });
    }

    // ... DrawingPanel inner class - No changes needed ...
    private class DrawingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); // Clears the panel
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw all stored shapes using their specific attributes
            for (ShapeData shape : shapes) {
                g2d.setColor(shape.getColor()); // Use the getColor() helper method
                g2d.fillOval(
                        (int) (shape.x - shape.size / 2.0),
                        (int) (shape.y - shape.size / 2.0),
                        shape.size,
                        shape.size);
            }
        }
    }

    // --- Mouse Listener Inner Class for Drawing ---
    private class DrawingMouseListener extends MouseAdapter implements MouseMotionListener {

        // Modified to pass roomName when publishing shape
        private void handleMouseEvent(MouseEvent e) {
            if (serverStub != null && roomName != null) { // Check roomName too
                double x = e.getX();
                double y = e.getY();
                ShapeData shape = new ShapeData(x, y, currentColor, currentSize);

                shapes.add(shape);
                drawingPanel.repaint();

                new Thread(() -> {
                    try {
                        // Pass roomName when publishing
                        serverStub.publishShape(shape, clientCallbackStub, roomName);
                    } catch (RemoteException ex) {
                        System.err
                                .println("Client: Error sending shape for room [" + roomName + "]: " + ex.getMessage());
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                                "Error sending drawing data: " + ex.getMessage(), "Communication Error",
                                JOptionPane.WARNING_MESSAGE));
                        SwingUtilities.invokeLater(() -> {
                            shapes.remove(shape);
                            drawingPanel.repaint();
                        });
                    }
                }).start();
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            handleMouseEvent(e);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            handleMouseEvent(e);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
        } // Not used
    }

    // Modified to pass roomName during unregistration
    private void shutdown() {
        System.out.println("Client: Shutting down...");
        // Unregister from server, passing roomName
        if (serverStub != null && clientCallbackStub != null && roomName != null) {
            try {
                serverStub.unregisterClient(clientCallbackStub, roomName); // Pass roomName
                System.out.println("Client: Unregistered from server room [" + roomName + "].");
            } catch (RemoteException e) {
                System.err.println("Client: Error unregistering from room [" + roomName + "]: " + e.getMessage());
            }
        }

        // ... rest of shutdown logic ...
        try {
            if (clientCallbackStub != null) {
                UnicastRemoteObject.unexportObject(this, true); // Force unexport
                System.out.println("Client: Callback object unexported.");
            }
        } catch (Exception e) {
            System.err.println("Client: Error unexporting object: " + e.getMessage());
        }

        if (frame != null) {
            frame.dispose();
        }
        System.exit(0);
    }

    // ... main method ...
    public static void main(String[] args) {
        // Prompt for room name happens inside the constructor now
        SwingUtilities.invokeLater(WhiteboardClient::new);
    }
}
