package dev.daly;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class WhiteboardClient implements ClientCallback {

    private static final String SERVER_URL = "rmi://localhost/WhiteboardService";
    private JFrame frame;
    private DrawingPanel drawingPanel;
    private WhiteboardServer serverStub;
    private ClientCallback clientCallbackStub;
    private final List<ShapeData> shapes = new CopyOnWriteArrayList<>();
    private Color currentColor = Color.BLACK;
    private int currentSize = 4;
    private String currentRoomName = null; // Start not in a room

    // GUI Components for Controls
    private JButton colorButton;
    private JLabel colorPreview;
    private JSlider sizeSlider;
    private JLabel sizeLabel;
    private JButton clearButton;
    private JButton saveButton;

    // GUI Components for Room Management
    private JLabel currentRoomLabel;
    private JList<String> roomList;
    private DefaultListModel<String> roomListModel;
    private JTextField roomNameField;
    private JButton createJoinButton;
    private JButton refreshButton;

    public WhiteboardClient() {
        // Initialize GUI components
        frame = new JFrame("Drawly");
        drawingPanel = new DrawingPanel();
        drawingPanel.setPreferredSize(new Dimension(800, 600));
        drawingPanel.setBackground(Color.WHITE);

        // --- Top Control Panel ---
        JPanel controlPanel = createControlPanel();

        // --- Right Room Panel ---
        JPanel roomPanel = createRoomPanel();

        // --- Main Layout ---
        frame.getContentPane().setLayout(new BorderLayout(5, 5));
        frame.getContentPane().add(controlPanel, BorderLayout.NORTH);
        frame.getContentPane().add(drawingPanel, BorderLayout.CENTER);
        frame.getContentPane().add(roomPanel, BorderLayout.EAST);

        // --- Frame Setup ---
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Initial state: disable drawing controls
        setDrawingEnabled(false);

        // Connect to RMI server (lookup stub only) and fetch initial room list
        connectToServer();
    }

    // Helper method to create the top control panel
    private JPanel createControlPanel() {
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        colorButton = new JButton("Choose Color");
        colorPreview = new JLabel("  ");
        colorPreview.setOpaque(true);
        colorPreview.setBackground(currentColor);
        colorPreview.setPreferredSize(new Dimension(20, 20));
        colorPreview.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        sizeSlider = new JSlider(JSlider.HORIZONTAL, 1, 50, currentSize);
        sizeLabel = new JLabel("Size: " + currentSize);
        sizeSlider.setPreferredSize(new Dimension(150, sizeSlider.getPreferredSize().height));

        clearButton = new JButton("Clear Room");
        saveButton = new JButton("Save Canvas");

        colorButton.addActionListener(e -> {
            Color chosenColor = JColorChooser.showDialog(frame, "Select Drawing Color", currentColor);
            if (chosenColor != null) {
                currentColor = chosenColor;
                colorPreview.setBackground(currentColor);
            }
        });

        sizeSlider.addChangeListener(e -> {
            currentSize = sizeSlider.getValue();
            sizeLabel.setText("Size: " + currentSize);
        });

        clearButton.addActionListener(e -> {
            if (serverStub != null && currentRoomName != null) { // Check if in a room
                int confirmation = JOptionPane.showConfirmDialog(frame,
                        "Are you sure you want to clear the whiteboard for room '" + currentRoomName + "'?",
                        "Confirm Clear Room", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                if (confirmation == JOptionPane.YES_OPTION) {
                    new Thread(() -> {
                        try {
                            System.out.println("Client: Sending clear request for room [" + currentRoomName + "]...");
                            serverStub.clearWhiteboard(currentRoomName);
                            System.out.println("Client: Clear request sent for room [" + currentRoomName + "].");
                        } catch (RemoteException ex) {
                            handleRemoteException("sending clear request", ex);
                        }
                    }).start();
                }
            } else {
                JOptionPane.showMessageDialog(frame, "You must be in a room to clear it.", "Not in Room",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });

        // Add ActionListener for the save button
        saveButton.addActionListener(e -> saveCanvasAsImage());

        controlPanel.add(colorButton);
        controlPanel.add(colorPreview);
        controlPanel.add(new JLabel("   "));
        controlPanel.add(sizeSlider);
        controlPanel.add(sizeLabel);
        controlPanel.add(new JLabel("   "));
        controlPanel.add(clearButton);
        controlPanel.add(new JLabel("   "));
        controlPanel.add(saveButton); 
        return controlPanel;
    }

    // Helper method to create the room management panel
    private JPanel createRoomPanel() {
        JPanel roomPanel = new JPanel();
        roomPanel.setLayout(new BoxLayout(roomPanel, BoxLayout.Y_AXIS));
        roomPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        currentRoomLabel = new JLabel("Current Room: Not Joined");
        currentRoomLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel availableRoomsLabel = new JLabel("Available Rooms:");
        availableRoomsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.setVisibleRowCount(10);
        // Add listener to update text field when list item is selected
        roomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selectedRoom = roomList.getSelectedValue();
                if (selectedRoom != null) {
                    roomNameField.setText(selectedRoom);
                }
            }
        });
        JScrollPane listScroller = new JScrollPane(roomList);
        listScroller.setAlignmentX(Component.LEFT_ALIGNMENT);
        listScroller.setPreferredSize(new Dimension(150, 150));
        listScroller.setMaximumSize(new Dimension(200, 300));

        refreshButton = new JButton("Refresh List");
        refreshButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshButton.addActionListener(e -> fetchAndDisplayRoomList());

        JLabel createJoinLabel = new JLabel("Create or Join Room:");
        createJoinLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        roomNameField = new JTextField(15);
        roomNameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        roomNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, roomNameField.getPreferredSize().height)); // Prevent
                                                                                                                 // vertical
                                                                                                                 // stretching

        createJoinButton = new JButton("Create / Join");
        createJoinButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        createJoinButton.addActionListener(e -> {
            String roomToJoin = roomNameField.getText().trim();
            if (!roomToJoin.isEmpty()) {
                switchToRoom(roomToJoin);
            } else {
                JOptionPane.showMessageDialog(frame, "Please enter or select a room name.", "Room Name Required",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        roomPanel.add(currentRoomLabel);
        roomPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacer
        roomPanel.add(availableRoomsLabel);
        roomPanel.add(listScroller);
        roomPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Spacer
        roomPanel.add(refreshButton);
        roomPanel.add(Box.createRigidArea(new Dimension(0, 15))); // Spacer
        roomPanel.add(createJoinLabel);
        roomPanel.add(roomNameField);
        roomPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Spacer
        roomPanel.add(createJoinButton);

        return roomPanel;
    }

    // Enable or disable drawing-related controls
    private void setDrawingEnabled(boolean enabled) {
        colorButton.setEnabled(enabled);
        sizeSlider.setEnabled(enabled);
        clearButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        drawingPanel.setEnabled(enabled);
        if (enabled) {
            drawingPanel.addMouseListener(new DrawingMouseListener());
            drawingPanel.addMouseMotionListener(new DrawingMouseListener());
        } else {
            drawingPanel.removeMouseListener(new DrawingMouseListener());
            drawingPanel.removeMouseMotionListener(new DrawingMouseListener());
        }
    }

    // Connects to server, gets stub, fetches initial room list
    private void connectToServer() {
        try {
            // Export this client object *once*
            clientCallbackStub = (ClientCallback) UnicastRemoteObject.exportObject(this, 0);
            System.out.println("Client: Callback object exported.");

            // Lookup the remote server object
            serverStub = (WhiteboardServer) Naming.lookup(SERVER_URL);
            System.out.println("Client: Connected to server stub.");

            // Fetch initial room list
            fetchAndDisplayRoomList();

        } catch (Exception e) {
            System.err.println("Client connection error: " + e.toString());
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error connecting to server: " + e.getMessage(), "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
            // Can't proceed without server connection
            shutdown();
        }
    }

    // Fetches room list from server and updates the JList
    private void fetchAndDisplayRoomList() {
        if (serverStub != null) {
            new Thread(() -> {
                try {
                    System.out.println("Client: Fetching room list...");
                    List<String> rooms = serverStub.getRoomList();
                    System.out.println("Client: Received rooms: " + rooms);
                    SwingUtilities.invokeLater(() -> {
                        roomListModel.clear();
                        if (rooms != null) {
                            for (String room : rooms) {
                                roomListModel.addElement(room);
                            }
                        }
                    });
                } catch (RemoteException e) {
                    handleRemoteException("fetching room list", e);
                }
            }).start();
        }
    }

    // Handles switching rooms (unregistering from old, registering to new)
    private void switchToRoom(String newRoomName) {
        if (newRoomName == null || newRoomName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Invalid room name.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newRoomName.equals(currentRoomName)) {
            System.out.println("Client: Already in room [" + newRoomName + "].");
            return;
        }

        System.out.println("Client: Attempting to switch to room [" + newRoomName + "]...");
        setDrawingEnabled(false);

        new Thread(() -> {
            final String oldRoomName = currentRoomName;
            try {
                // Unregister from the current room, if any
                if (oldRoomName != null && clientCallbackStub != null && serverStub != null) {
                    try {
                        System.out.println("Client: Unregistering from room [" + oldRoomName + "]...");
                        serverStub.unregisterClient(clientCallbackStub, oldRoomName);
                        System.out.println("Client: Unregistered from room [" + oldRoomName + "].");
                    } catch (RemoteException e) {
                        System.err.println(
                                "Client: Error unregistering from room [" + oldRoomName + "]: " + e.getMessage());
                    }
                }

                // Update state before registering to new room
                currentRoomName = newRoomName;

                // Clear local canvas immediately
                SwingUtilities.invokeLater(() -> {
                    shapes.clear();
                    drawingPanel.repaint();
                    currentRoomLabel.setText("Current Room: " + currentRoomName);
                    frame.setTitle("Drawly - Room: " + currentRoomName);
                });

                // Register with the new room
                if (serverStub != null && clientCallbackStub != null) {
                    System.out.println("Client: Registering for room [" + currentRoomName + "]...");
                    serverStub.registerClient(clientCallbackStub, currentRoomName);
                    // Server will call initializeCanvas with shapes for the new room
                    System.out.println("Client: Registration request sent for room [" + currentRoomName + "].");

                    // 3. Enable drawing controls and refresh room list
                    SwingUtilities.invokeLater(() -> {
                        setDrawingEnabled(true);
                        fetchAndDisplayRoomList();
                    });
                }

            } catch (RemoteException e) {
                // Failed to register with the new room, revert state
                currentRoomName = oldRoomName; // Revert room name
                SwingUtilities.invokeLater(() -> {
                    currentRoomLabel.setText(
                            currentRoomName == null ? "Current Room: Not Joined" : "Current Room: " + currentRoomName);
                    frame.setTitle(currentRoomName == null ? "Drawly" : "Drawly - Room: " + currentRoomName);
                    setDrawingEnabled(currentRoomName != null); // Re-enable if we reverted to a valid room
                    handleRemoteException("switching to room [" + newRoomName + "]", e);
                });
            } catch (Exception e) {
                // Handle other potential errors during switch
                currentRoomName = oldRoomName; // Revert room name
                SwingUtilities.invokeLater(() -> {
                    currentRoomLabel.setText(
                            currentRoomName == null ? "Current Room: Not Joined" : "Current Room: " + currentRoomName);
                    frame.setTitle(currentRoomName == null ? "Drawly" : "Drawly - Room: " + currentRoomName);
                    setDrawingEnabled(currentRoomName != null);
                    JOptionPane.showMessageDialog(frame,
                            "An unexpected error occurred while switching rooms: " + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                    e.printStackTrace();
                });
            }
        }).start();
    }

    // --- ClientCallback Implementation ---
    @Override
    public void updateCanvas(ShapeData shape) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            shapes.add(shape);
            drawingPanel.repaint();
        });
    }

    @Override
    public void initializeCanvas(List<ShapeData> initialShapes) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            System.out.println("Client: Receiving initial shapes for room [" + currentRoomName + "]: "
                    + (initialShapes != null ? initialShapes.size() : 0));
            shapes.clear();
            if (initialShapes != null) {
                shapes.addAll(initialShapes);
            }
            drawingPanel.repaint();
        });
    }

    @Override
    public void clearCanvas() throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            System.out.println("Client: Received clear command for room [" + currentRoomName + "]. Clearing canvas.");
            shapes.clear();
            drawingPanel.repaint();
        });
    }

    // --- Drawing Panel Inner Class ---
    private class DrawingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (ShapeData shape : shapes) {
                g2d.setColor(shape.getColor());
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
            // Only draw if we have a server connection and are in a room
            if (serverStub != null && currentRoomName != null) {
                double x = e.getX();
                double y = e.getY();
                ShapeData shape = new ShapeData(x, y, currentColor, currentSize);

                shapes.add(shape);
                drawingPanel.repaint();

                new Thread(() -> {
                    try {
                        serverStub.publishShape(shape, clientCallbackStub, currentRoomName);
                    } catch (RemoteException ex) {
                        // Use helper for consistent error handling
                        handleRemoteException("sending drawing data", ex);
                        // If sending failed, remove the locally added shape for consistency
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
        }
    }

    // --- Shutdown Handling ---
    private void shutdown() {
        System.out.println("Client: Shutting down...");
        // Unregister from server, only if in a room
        if (serverStub != null && clientCallbackStub != null && currentRoomName != null) {
            try {
                serverStub.unregisterClient(clientCallbackStub, currentRoomName);
                System.out.println("Client: Unregistered from server room [" + currentRoomName + "].");
            } catch (RemoteException e) {
                // Log but continue shutdown
                System.err
                        .println("Client: Error unregistering from room [" + currentRoomName + "]: " + e.getMessage());
            }
        }

        // Unexport the client object (regardless of room status)
        try {
            if (clientCallbackStub != null) {
                UnicastRemoteObject.unexportObject(this, true);
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

    // --- Method to save the canvas content as an image ---
    private void saveCanvasAsImage() {
        if (drawingPanel == null || !drawingPanel.isEnabled()) {
            JOptionPane.showMessageDialog(frame, "Cannot save canvas when not in a room.", "Save Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Canvas As PNG");
        // Set default filename suggestion
        fileChooser.setSelectedFile(
                new File("whiteboard_" + (currentRoomName != null ? currentRoomName : "untitled") + ".png"));
        // Filter for PNG files
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG Images (*.png)", "png"));

        int userSelection = fileChooser.showSaveDialog(frame);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            // Ensure the file has a .png extension
            String filePath = fileToSave.getAbsolutePath();
            if (!filePath.toLowerCase().endsWith(".png")) {
                fileToSave = new File(filePath + ".png");
            }

            // Check for overwrite confirmation
            if (fileToSave.exists()) {
                int result = JOptionPane.showConfirmDialog(frame,
                        "The file already exists. Do you want to overwrite it?",
                        "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
                if (result == JOptionPane.NO_OPTION) {
                    return;
                }
            }

            try {
                // Create a BufferedImage of the drawing panel's size
                int width = drawingPanel.getWidth();
                int height = drawingPanel.getHeight();
                // Use TYPE_INT_ARGB to support transparency if needed, otherwise TYPE_INT_RGB
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = image.createGraphics();

                // Paint the drawing panel's content onto the BufferedImage
                // Ensure background is painted white first
                g2d.setColor(drawingPanel.getBackground());
                g2d.fillRect(0, 0, width, height);
                drawingPanel.paintComponent(g2d); // Call paintComponent to draw shapes
                g2d.dispose(); // Release graphics resources

                // Write the BufferedImage to the selected file as PNG
                boolean success = ImageIO.write(image, "png", fileToSave);

                if (success) {
                    JOptionPane.showMessageDialog(frame, "Canvas saved successfully as:\n" + fileToSave.getName(),
                            "Save Successful", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(frame,
                            "Failed to save canvas.\nImageIO writer not found for PNG format.", "Save Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Error saving canvas: " + ex.getMessage(), "Save Error",
                        JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "An unexpected error occurred during save: " + ex.getMessage(),
                        "Save Error", JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }

    // Helper method for handling RemoteExceptions consistently
    private void handleRemoteException(String actionDescription, RemoteException ex) {
        System.err.println("Client: Error " + actionDescription + ": " + ex.getMessage());
        ex.printStackTrace();
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                "Communication error while " + actionDescription + ":\n" + ex.getMessage() +
                        "\nPlease check server connection and try again.",
                "Communication Error",
                JOptionPane.ERROR_MESSAGE));
    }

    // --- Application Entry Point ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(WhiteboardClient::new);
    }
}
