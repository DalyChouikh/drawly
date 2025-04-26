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

    // Store shapes to be drawn. Use a thread-safe list because updates
    // can come from the RMI thread.
    private final List<ShapeData> shapes = new CopyOnWriteArrayList<>();

    // Current drawing attributes
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
            serverStub.registerClient(clientCallbackStub);

            System.out.println("Client: Connected to server and registered.");

        } catch (Exception e) {
            System.err.println("Client connection error: " + e.toString());
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error connecting to server: " + e.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
            // Optionally exit or disable drawing features
            shutdown(); // Clean up if connection fails
        }
    }

    // --- ClientCallback Implementation ---
    @Override
    public void updateCanvas(ShapeData shape) throws RemoteException {
        // This method is called by an RMI thread.
        // Use SwingUtilities.invokeLater to safely update the GUI from the Event Dispatch Thread (EDT).
        SwingUtilities.invokeLater(() -> {
            shapes.add(shape);      // Add shape to our list
            drawingPanel.repaint(); // Request redraw
        });
    }

    // --- Drawing Panel Inner Class ---
    private class DrawingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); // Clears the panel
            Graphics2D g2d = (Graphics2D) g;
            // Improve rendering quality
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw all stored shapes using their specific attributes
            for (ShapeData shape : shapes) {
                g2d.setColor(shape.color); // Set color from shape data
                // Draw an oval centered at (x, y) with the shape's size
                g2d.fillOval(
                    (int) (shape.x - shape.size / 2.0),
                    (int) (shape.y - shape.size / 2.0),
                    shape.size,
                    shape.size
                );
            }
        }
    }

    // --- Mouse Listener Inner Class for Drawing ---
    private class DrawingMouseListener extends MouseAdapter implements MouseMotionListener {

        private void handleMouseEvent(MouseEvent e) {
            if (serverStub != null) {
                double x = e.getX();
                double y = e.getY();

                // Create shape data with current attributes
                ShapeData shape = new ShapeData(x, y, currentColor, currentSize); // Use current color and size

                // Add locally and repaint immediately for responsiveness
                shapes.add(shape);
                drawingPanel.repaint();

                // Send shape data to the server in a background thread
                // to avoid blocking the EDT
                new Thread(() -> {
                    try {
                        // Pass our own callback stub so server doesn't send it back to us
                        serverStub.publishShape(shape, clientCallbackStub);
                    } catch (RemoteException ex) {
                        System.err.println("Client: Error sending shape: " + ex.getMessage());
                        // Handle server communication error (e.g., show message)
                        SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "Error sending drawing data: " + ex.getMessage(), "Communication Error", JOptionPane.WARNING_MESSAGE)
                        );
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

        // Need to implement all methods from MouseMotionListener
        @Override
        public void mouseMoved(MouseEvent e) { } // Not used
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
