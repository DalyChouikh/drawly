package dev.daly;

import java.awt.Color; // Import Color
import java.io.Serial;
import java.io.Serializable;

// Must be Serializable to be sent via RMI
public class ShapeData implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L; // Increment version ID due to changes
    public double x;
    public double y;
    public Color color; // Add color
    public int size;    // Add size

    public ShapeData(double x, double y, Color color, int size) {
        this.x = x;
        this.y = y;
        this.color = color;
        this.size = size;
    }
}
