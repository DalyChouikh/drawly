package dev.daly;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList; // Thread-safe list for shapes

public class WhiteboardClient implements ClientCallback {

    private static final String SERVER_URL = "//localhost/WhiteboardService"; // Or server IP/hostname
    private JFrame frame;
    private DrawingPanel drawingPanel;
    private WhiteboardServer serverStub;
    private ClientCallback clientCallbackStub; // Stub for *this* client object
    private final List<ShapeData> shapes = new CopyOnWriteArrayList<>();
    private Color currentColor = Color.BLACK;
    private int currentSize = 4;
    private JLabel colorPreview; // To show selected color

    public WhiteboardClient() {
        // Initialize GUI components
        frame = new JFrame("Drawly");
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

        controlPanel.add(colorButton);
        controlPanel.add(colorPreview);
        controlPanel.add(new JLabel("   ")); // Spacer
        controlPanel.add(sizeSlider);
        controlPanel.add(sizeLabel);
        // --- End Control Panel ---

        // Add mouse listeners for drawing
        DrawingMouseListener mouseListener = new DrawingMouseListener();
        drawingPanel.addMouseListener(mouseListener);
        drawingPanel.addMouseMotionListener(mouseListener);

        frame.getContentPane().add(controlPanel, BorderLayout.NORTH); // Add controls at the top
        frame.getContentPane().add(drawingPanel, BorderLayout.CENTER);

        // Handle window closing
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // We handle close manually
        frame.pack();
        frame.setLocationRelativeTo(null); // Center the window
        frame.setVisible(true);

        // Connect to RMI server
        connectToServer();
    }

    private void connectToServer() {
        try {
            // Export this client object to make it remotely accessible for callbacks
            clientCallbackStub = (ClientCallback) UnicastRemoteObject.exportObject(this, 0); // 0 for anonymous port

            // Lookup the remote server object
            serverStub = (WhiteboardServer) Naming.lookup(SERVER_URL);

            // Register this client with the server
            // The server will now call initializeCanvas upon successful registration
            serverStub.registerClient(clientCallbackStub);

            System.out.println("Client: Connected to server and registered.");

        } catch (Exception e) {
            System.err.println("Client connection error: " + e.toString());
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error connecting to server: " + e.getMessage(), "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
            // Optionally exit or disable drawing features
            shutdown(); // Clean up if connection fails
        }
    }

    // --- ClientCallback Implementation ---
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

    // --- Drawing Panel Inner Class ---
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

        private void handleMouseEvent(MouseEvent e) {
            if (serverStub != null) {
                double x = e.getX();
                double y = e.getY();

                // Create shape data with current attributes using the appropriate constructor
                ShapeData shape = new ShapeData(x, y, currentColor, currentSize);

                // OPTIONAL: Add locally immediately for responsiveness.
                // The shape will be added *again* when broadcast back via updateCanvas.
                // If initializeCanvas clears the list, this temporary add is okay.
                // If consistency is paramount, remove this local add and rely solely on server
                // broadcast.
                shapes.add(shape);
                drawingPanel.repaint();

                // Send shape data to the server in a background thread
                new Thread(() -> {
                    try {
                        serverStub.publishShape(shape, clientCallbackStub);
                    } catch (RemoteException ex) {
                        System.err.println("Client: Error sending shape: " + ex.getMessage());
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                                "Error sending drawing data: " + ex.getMessage(), "Communication Error",
                                JOptionPane.WARNING_MESSAGE));
                        // Optional: Remove the locally added shape if sending failed?
                        // SwingUtilities.invokeLater(() -> {
                        // shapes.remove(shape); // Might be tricky if duplicates exist
                        // drawingPanel.repaint();
                        // });
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

    // --- Shutdown Handling ---
    private void shutdown() {
        System.out.println("Client: Shutting down...");
        // Unregister from server
        if (serverStub != null && clientCallbackStub != null) {
            try {
                serverStub.unregisterClient(clientCallbackStub);
                System.out.println("Client: Unregistered from server.");
            } catch (RemoteException e) {
                System.err.println("Client: Error unregistering: " + e.getMessage());
            }
        }

        // Unexport the client object
        try {
            if (clientCallbackStub != null) {
                UnicastRemoteObject.unexportObject(this, true); // Force unexport
                System.out.println("Client: Callback object unexported.");
            }
        } catch (Exception e) {
            System.err.println("Client: Error unexporting object: " + e.getMessage());
        }

        // Close the Swing window
        if (frame != null) {
            frame.dispose();
        }

        // Ensure the application exits completely
        System.exit(0);
    }

    // Application entry point
    public static void main(String[] args) {
        // Create and show the GUI on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(WhiteboardClient::new);
    }
}
