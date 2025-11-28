package physics;

import java.awt.Color;
import model.Model;

/**
 * Bola controlable por el jugador usando las teclas de dirección.
 * Hereda de Ball pero permite control manual de aceleración.
 */
public class PlayerBall extends Ball {
    // Aceleración aplicada cuando se presiona una tecla (px/ms²)
    private static final double CONTROL_ACCELERATION = 0.001; // ajustable
    // Velocidad máxima permitida para el jugador (px/ms)
    private static final double MAX_SPEED = 0.5; // ajustable
    
    // Estados de teclas presionadas
    private volatile boolean upPressed = false;
    private volatile boolean downPressed = false;
    private volatile boolean leftPressed = false;
    private volatile boolean rightPressed = false;
    
    /**
     * Constructor: crea una bola de jugador en el centro del área
     */
    public PlayerBall(Model model) {
        super(model, 15); // radio fijo de 15 para el jugador
        
        // Posicionar en el centro del área
        int centerX = model.getAreaWidth() / 2;
        int centerY = model.getAreaHeight() / 2;
        setXDouble(centerX);
        setYDouble(centerY);
        
        // Velocidad inicial cero (el jugador controla el movimiento)
        setVx(0);
        setVy(0);
    }
    
    /**
     * Método que actualiza la aceleración basándose en las teclas presionadas
     * Debe llamarse antes de move() en cada frame
     */
    public void updateControlAcceleration() {
        double ax = 0;
        double ay = 0;
        
        // Calcular aceleración según teclas presionadas
        if (leftPressed) ax -= CONTROL_ACCELERATION;
        if (rightPressed) ax += CONTROL_ACCELERATION;
        if (upPressed) ay -= CONTROL_ACCELERATION;
        if (downPressed) ay += CONTROL_ACCELERATION;
        
        // Aplicar la aceleración
        setAcceleration(ax, ay);
    }
    
    /**
     * Override de move para aplicar límite de velocidad máxima
     */
    @Override
    public void move(double dt) {
        // Primero actualizar aceleración según controles
        updateControlAcceleration();
        
        // Mover normalmente
        super.move(dt);
        
        // Limitar velocidad máxima
        double vx = getVx();
        double vy = getVy();
        double speed = Math.sqrt(vx * vx + vy * vy);
        
        if (speed > MAX_SPEED) {
            double factor = MAX_SPEED / speed;
            setVx(vx * factor);
            setVy(vy * factor);
        }
        
        // Aplicar fricción suave para que la bola se detenga gradualmente
        // si no se presiona ninguna tecla
        if (!upPressed && !downPressed && !leftPressed && !rightPressed) {
            setVx(vx * 0.98); // 2% de fricción por frame
            setVy(vy * 0.98);
        }
    }
    
    /**
     * Obtener el color especial del jugador (azul brillante)
     */
    @Override
    public Color getColor() {
        return new Color(30, 144, 255); // DodgerBlue
    }
    
    // Métodos para establecer el estado de las teclas
    public void setUpPressed(boolean pressed) { this.upPressed = pressed; }
    public void setDownPressed(boolean pressed) { this.downPressed = pressed; }
    public void setLeftPressed(boolean pressed) { this.leftPressed = pressed; }
    public void setRightPressed(boolean pressed) { this.rightPressed = pressed; }
    
    // Getters para verificar estado (útil para debug)
    public boolean isUpPressed() { return upPressed; }
    public boolean isDownPressed() { return downPressed; }
    public boolean isLeftPressed() { return leftPressed; }
    public boolean isRightPressed() { return rightPressed; }
}
