package dev.daly;

import java.io.Serial;
import java.io.Serializable;

// Must be Serializable to be sent via RMI
public class ShapeData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    public double x;
    public double y;
    // Could add color, shape type, size etc. later

    public ShapeData(double x, double y) {
        this.x = x;
        this.y = y;
    }
}