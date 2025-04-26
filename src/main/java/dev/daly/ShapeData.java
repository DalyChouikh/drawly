package dev.daly;

import java.awt.Color;
import java.io.Serial;
import java.io.Serializable;

// Must be Serializable to be sent via RMI
public class ShapeData implements Serializable {
    @Serial
    private static final long serialVersionUID = 3L; // Increment version ID due to changes
    public double x;
    public double y;
    // Store color as RGB components for easier DB storage
    public int colorR;
    public int colorG;
    public int colorB;
    public int size;

    // Constructor accepting Color object
    public ShapeData(double x, double y, Color color, int size) {
        this.x = x;
        this.y = y;
        this.colorR = color.getRed();
        this.colorG = color.getGreen();
        this.colorB = color.getBlue();
        this.size = size;
    }

    // Constructor accepting RGB values (useful when loading from DB)
    public ShapeData(double x, double y, int r, int g, int b, int size) {
        this.x = x;
        this.y = y;
        this.colorR = r;
        this.colorG = g;
        this.colorB = b;
        this.size = size;
    }

    // Helper method to get Color object
    public Color getColor() {
        return new Color(colorR, colorG, colorB);
    }
}
