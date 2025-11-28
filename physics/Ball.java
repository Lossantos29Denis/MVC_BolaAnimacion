// Paquete physics: contiene las clases relacionadas con el modelo físico
package physics;

// Import de Color para pintar la bola
import java.awt.Color;
import java.util.concurrent.ThreadLocalRandom;
import model.Model;

// Clase que representa una bola en la simulación. Implementa Runnable
// para permitir, opcionalmente, ejecutar un hilo por bola.
public class Ball implements Runnable {
    /**
     * Radio de la bola en píxeles (constante tras la creación).
     */
    // Radio de la bola (px), inmutable después de la construcción.
    private final int radius;
    /**
     * Color de la bola.
     */
    // Color de la bola, usado por la vista al dibujarla.
    private final Color color;
    /**
     * Contador de colisiones con otras bolas. Se usa para eliminar bolas después
     * de un número determinado de impactos.
     */
    // Contador de impactos con otras bolas; se usa para eliminar bolas tras X impactos.
    public int hitCount = 0; // número de colisiones con otras bolas

    /**
    /**
     * Referencia al modelo para acceder a estado de pausa y monitor
     */
    // NOTIFYALL: Referencia al modelo para sincronización de pausa
    protected final Model model;

    /**
     * BasicPhysicalModel: clase interna que encapsula las propiedades físicas básicas de la bola.
     * Contiene posición, velocidad y masa, junto con métodos para actualización física.
     * Esto permite separar la lógica física de la representación visual.
     */
    private class BasicPhysicalModel {
        // Posición en coordenadas de punto flotante (px)
        private double x, y;
        // Componentes de velocidad en píxeles por milisegundo (px/ms)
        private double vx, vy;
        // Componentes de aceleración en píxeles por milisegundo² (px/ms²)
        private double ax, ay;
        // Masa de la bola calculada a partir del radio (proporcional al área: πr²)
        private final double mass;

        // Constructor: inicializa posición, velocidad y calcula la masa
        BasicPhysicalModel(double x, double y, double vx, double vy, int radius) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.ax = 0.0; // sin aceleración horizontal por defecto
            this.ay = 0.0; // sin aceleración vertical por defecto (sin gravedad)
            // Masa proporcional al área (simplificamos sin π para rendimiento)
            this.mass = radius * radius;
        }

        // Integración de movimiento: actualiza velocidad según aceleración y posición según velocidad
        // dt debe estar en milisegundos
        void integrate(double dt) {
            // Actualizar velocidad con la aceleración (v = v + a*dt)
            this.vx += this.ax * dt;
            this.vy += this.ay * dt;
            // Actualizar posición con la velocidad (x = x + v*dt)
            this.x += this.vx * dt;
            this.y += this.vy * dt;
        }

        // Aplicar un impulso (cambio instantáneo de velocidad considerando masa)
        void applyImpulse(double ix, double iy) {
            if (mass > 0) {
                this.vx += ix / mass;
                this.vy += iy / mass;
            }
        }

        // Getters y setters para acceso controlado desde Ball
        double getX() { return x; }
        double getY() { return y; }
        double getVx() { return vx; }
        double getVy() { return vy; }
        double getAx() { return ax; }
        double getAy() { return ay; }
        double getMass() { return mass; }
        void setX(double x) { this.x = x; }
        void setY(double y) { this.y = y; }
        void setVx(double vx) { this.vx = vx; }
        void setVy(double vy) { this.vy = vy; }
        void setAx(double ax) { this.ax = ax; }
        void setAy(double ay) { this.ay = ay; }
    }

    // Modelo físico interno de la bola (se inicializa en los constructores)
    private BasicPhysicalModel physics;

    // Hilo opcional que puede ejecutar esta bola de forma independiente.
    // No se inicia por defecto: el `Model` central actualiza todas las bolas.
    // Si se quiere, llamar a startRunner() para ejecutar este Runnable en su propio hilo.
    private volatile Thread runnerThread = null;

    // Constructor que crea una bola con radio aleatorio dentro de un rango.
    // Recibe el `Model` para conocer el área y el recuadro central y así evitar
    // colocar la bola inicialmente dentro del recuadro.
    public Ball(Model model) {
        // NOTIFYALL: Guardar referencia al modelo para sincronización
        this.model = model;

        // Generador aleatorio local por hilo (más eficiente en concurrencia)
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // Asignar un radio aleatorio entre 8 y 19 (inclusive en el límite inferior)
        this.radius = rnd.nextInt(8, 20);
        // Intentamos generar la bola fuera del recuadro central. Si tras varios
        // intentos seguimos dentro, se aplica una posición de fallback.
    // Preparar intentos para posicionamiento y obtener rect del recuadro central
    int attempts = 0;
    int maxAttempts = 50;
    int bx = model.getBoxX();
    int by = model.getBoxY();
    int bw = model.getBoxWidth();
    int bh = model.getBoxHeight();
    double candX, candY; // candidatos de posición
        // Intentar generar una posición aleatoria fuera del recuadro central
        do {
            // coordenadas candidatas dentro del área válida (evitar salir del borde)
            candX = rnd.nextDouble(radius, Math.max(radius + 1, model.getAreaWidth() - radius));
            candY = rnd.nextDouble(radius, Math.max(radius + 1, model.getAreaHeight() - radius));
            attempts++;
            // comprobar si la bola quedaría completamente dentro del recuadro
            boolean fullyInsideBox = (candX - radius >= bx && candX + radius <= bx + bw && candY - radius >= by && candY + radius <= by + bh);
            // si no queda totalmente dentro, aceptamos la posición
            if (!fullyInsideBox) break;
        } while (attempts < maxAttempts);
        // si tras los intentos todavía está dentro, forzamos posicionarla fuera
        // (a la izquierda) como medida de seguridad
        // Si tras varios intentos no encontramos una posición fuera del recuadro,
        // forzamos una posición de fallback (junto al recuadro) como medida de seguridad.
        if (attempts >= maxAttempts) {
            candX = Math.max(radius, bx - radius - 5);
            candY = Math.min(Math.max(radius, by + bh + radius + 5), model.getAreaHeight() - radius);
        }
        
        // Velocidad (magnitud) aleatoria y dirección aleatoria (ángulo en radianes)
        double speed = rnd.nextDouble(60.0, 180.0); // rango de velocidad en px/s
        // Convertir a px/ms dividiendo entre 1000
        speed = speed / 1000.0; // ahora speed está en px/ms
        double angle = rnd.nextDouble(0, Math.PI * 2); // dirección completa
        // Descomponer en componentes vx, vy (en px/ms)
        double vxInit = Math.cos(angle) * speed;
        double vyInit = Math.sin(angle) * speed;
        
        // Inicializar el modelo físico con posición y velocidad calculadas
        this.physics = new BasicPhysicalModel(candX, candY, vxInit, vyInit, radius);
        
        // Color aleatorio con tonos no demasiado oscuros ni demasiado claros
        this.color = new Color(rnd.nextInt(40, 220), rnd.nextInt(40, 220), rnd.nextInt(40, 220));
    }

    // Constructor alternativo: crear bola con radio explícito.
    public Ball(Model model, int radius) {
        // NOTIFYALL: Guardar referencia al modelo para sincronización
        this.model = model;

        // generador aleatorio local
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // asegurar que el radio mínimo sea 1
        this.radius = Math.max(1, radius);
        // Intento de generación fuera del recuadro central (misma lógica que el
        // constructor sin radio explícito).
    // repetir la misma lógica de posicionamiento que en el otro constructor
    int attempts = 0;
    int maxAttempts = 50;
    int bx = model.getBoxX();
    int by = model.getBoxY();
    int bw = model.getBoxWidth();
    int bh = model.getBoxHeight();
    double candX, candY;
        // Generar candidata dentro del área, evitando recuadro
        do {
            candX = rnd.nextDouble(this.radius, Math.max(this.radius + 1, model.getAreaWidth() - this.radius));
            candY = rnd.nextDouble(this.radius, Math.max(this.radius + 1, model.getAreaHeight() - this.radius));
            attempts++;
            boolean fullyInsideBox = (candX - this.radius >= bx && candX + this.radius <= bx + bw && candY - this.radius >= by && candY + this.radius <= by + bh);
            if (!fullyInsideBox) break;
        } while (attempts < maxAttempts);
        if (attempts >= maxAttempts) {
            // fallback si no se encontró buena posición
            candX = Math.max(this.radius, bx - this.radius - 5);
            candY = Math.min(Math.max(this.radius, by + bh + this.radius + 5), model.getAreaHeight() - this.radius);
        }
        
        // Velocidad y dirección aleatorias (misma lógica que en el otro constructor)
        double speed = rnd.nextDouble(60.0, 180.0);
        // Convertir a px/ms dividiendo entre 1000
        speed = speed / 1000.0; // ahora speed está en px/ms
        double angle = rnd.nextDouble(0, Math.PI * 2);
        double vxInit = Math.cos(angle) * speed;
        double vyInit = Math.sin(angle) * speed;
        
        // Inicializar el modelo físico con posición y velocidad calculadas
        this.physics = new BasicPhysicalModel(candX, candY, vxInit, vyInit, this.radius);
        
        // Color aleatorio
        this.color = new Color(rnd.nextInt(40, 220), rnd.nextInt(40, 220), rnd.nextInt(40, 220));
    }

    // Getter público para la coordenada X redondeada a entero (para dibujar)
    public int getX() { return (int) Math.round(physics.getX()); }
    // Getter público para la coordenada Y redondeada a entero (para dibujar)
    public int getY() { return (int) Math.round(physics.getY()); }
    // Obtener el radio como entero
    public int getRadius() { return radius; }
    // Obtener el color de la bola
    public Color getColor() { return color; }

    // Método sincronizado para proteger la actualización de posición frente a accesos concurrentes
    // dt debe estar en milisegundos
    // Optimización: synchronized solo necesario si hay múltiples threads modificando
    public void move(double dt) {
        // Actualiza posición integrando la velocidad sobre dt (milisegundos).
        physics.integrate(dt);
    }

    // --- Métodos para acceder/modificar posición y velocidad ---
    // Optimización: eliminar synchronized cuando solo hay un thread actualizador (Model)
    // Si se usan runners individuales por bola, restaurar synchronized
    // Devolver/establecer posición X como double
    public double getXDouble() { return physics.getX(); }
    public double getYDouble() { return physics.getY(); }
    public void setXDouble(double nx) { physics.setX(nx); }
    public void setYDouble(double ny) { physics.setY(ny); }

    // Devolver/establecer componentes de velocidad
    public double getVx() { return physics.getVx(); }
    public double getVy() { return physics.getVy(); }
    public void setVx(double nvx) { physics.setVx(nvx); }
    public void setVy(double nvy) { physics.setVy(nvy); }

    // Devolver/establecer componentes de aceleración
    public double getAx() { return physics.getAx(); }
    public double getAy() { return physics.getAy(); }
    public void setAx(double nax) { physics.setAx(nax); }
    public void setAy(double nay) { physics.setAy(nay); }

    /**
     * Aplica una aceleración constante (por ejemplo, gravedad).
     * @param ax aceleración en eje X (px/ms²)
     * @param ay aceleración en eje Y (px/ms²)
     */
    public void setAcceleration(double ax, double ay) {
        physics.setAx(ax);
        physics.setAy(ay);
    }

    /**
     * Implementación de Runnable. Ejecuta un bucle simple que mueve la bola
     * a intervalos regulares (aprox. 60Hz). Si el hilo es interrumpido, el
     * bucle termina. NOTA: si la simulación del programa ya usa el hilo
     * central del `Model` para actualizar posiciones, arrancar este runner
     * provocará que la bola se mueva dos veces por tick. Por eso el runner
     * no se inicia automáticamente.
     */
    @Override
    public void run() {
        // Objetivo de intervalo en ms (~60Hz)
        final long targetMs = 16; // ~60Hz
        // Bucle principal del runnable; se detiene cuando el hilo es interrumpido
        while (!Thread.currentThread().isInterrupted()) {
            long start = System.nanoTime(); // medir tiempo de inicio para ajustar sleep
            try {
                // NOTIFYALL: Esperar si la simulación está pausada
                Object pauseMonitor = model.getPauseMonitor();
                synchronized (pauseMonitor) {
                    while (model.isPaused() && !Thread.currentThread().isInterrupted()) {
                        try {
                            pauseMonitor.wait();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // aplicar un paso con dt en milisegundos
                move(targetMs);
                // tiempo que tomó la actualización en ms
                long tookMs = (System.nanoTime() - start) / 1_000_000;
                // calcular tiempo restante a dormir para mantener ~60Hz
                long sleep = Math.max(1, targetMs - tookMs);
                Thread.sleep(sleep);
            } catch (InterruptedException ex) {
                // Si se interrumpe, reponer flag de interrupción y salir
                Thread.currentThread().interrupt();
                break;
            }

        }
    }

    /** Inicia un hilo daemon que ejecuta el runner de esta bola. No hace nada si ya está iniciado. */
    public void startRunner() {
        // si ya existe y está vivo, no iniciar otro
        if (runnerThread != null && runnerThread.isAlive()) return;
        // crear y arrancar el hilo como daemon
        runnerThread = new Thread(this, "Ball-Runner");
        runnerThread.setDaemon(true);
        runnerThread.start();
    }

    /** Detiene el runner de esta bola si está en ejecución. */
    public void stopRunner() {
        if (runnerThread != null) {
            // solicitar detención interrumpiendo el hilo
            runnerThread.interrupt();
            runnerThread = null;
        }
    }
}
