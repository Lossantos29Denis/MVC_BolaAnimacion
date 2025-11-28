package view;

// Importa utilidades AWT/Swing para la interfaz y dibujo
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import model.Model;
import physics.Ball;
import physics.PlayerBall;

// Vista principal del patrón MVC: contiene el panel de juego (GamePanel)
// y el panel de control (ControlPanel). Escucha cambios en el Model.
public class View extends JFrame implements Model.ModelListener {
    // Referencia al modelo para consultar estado (bolas, dimensiones, recuadro)
    private final Model model;
    // Panel donde se renderiza la simulación
    private final GamePanel gamePanel;
    // Panel con controles y estadísticas
    private final ControlPanel controlPanel;
    // División entre área de juego y panel de control
    private JSplitPane split;

    // Constructor: crea la ventana, los paneles y los registra como listener del modelo
    public View(Model model) {
        super("MVC Bola Animación");
        this.model = model;
        this.model.addListener(this); // registrar para recibir cambios

        gamePanel = new GamePanel(model);
        controlPanel = new ControlPanel();

        // Split horizontal: izquierda juego, derecha controles
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, gamePanel, controlPanel);
        split.setDividerLocation(700);
        split.setResizeWeight(0);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        setSize(900, 500);
        setLocationRelativeTo(null); // centrar ventana

        // Asegurar tamaños preferidos del panel de juego basados en el modelo
        gamePanel.setPreferredSize(new Dimension(model.getAreaWidth(), model.getAreaHeight()));
        gamePanel.setMinimumSize(new Dimension(model.getAreaWidth(), model.getAreaHeight()));
        gamePanel.setMaximumSize(new Dimension(model.getAreaWidth(), model.getAreaHeight()));
    }

    /**
     * Panel que muestra información ligera sobre los hilos de la JVM.
     * Usa un temporizador para refrescar periódicamente la lista.
     */
    public static class ThreadsPanel extends JPanel {
        private final JPanel listPanel = new JPanel(); // contenedor vertical de filas
        // Cache de las últimas filas creadas para reutilizar componentes
        private final java.util.Map<Long, JPanel> rowCache = new java.util.HashMap<>();
        
        public ThreadsPanel() {
            setLayout(new BorderLayout(6,6));
            listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
            JScrollPane sp = new JScrollPane(listPanel);
            add(sp, BorderLayout.CENTER);
            
            // Activar doble buffering para reducir parpadeo
            setDoubleBuffered(true);
            listPanel.setDoubleBuffered(true);

            // temporizador de refresco (cada 400 ms)
            Timer t = new Timer(400, e -> refreshThreads());
            t.start();
        }

        // Reconstruir visualmente la lista de hilos con una barra y texto por hilo
        // OPTIMIZADO: Reutilizar componentes existentes en lugar de recrear todo
        private void refreshThreads() {
            ThreadMXBeanHelper helper = ThreadMXBeanHelper.getInstance();
            java.util.List<ThreadInfoLite> infos = helper.getThreadInfos();
            
            // Crear set de IDs actuales para saber qué hilos siguen vivos
            java.util.Set<Long> currentIds = new java.util.HashSet<>();
            for (ThreadInfoLite info : infos) {
                currentIds.add(info.id);
            }
            
            // Remover hilos que ya no existen
            rowCache.keySet().removeIf(id -> !currentIds.contains(id));
            
            // Actualizar o crear filas
            listPanel.removeAll();
            for (ThreadInfoLite info : infos) {
                JPanel row = rowCache.get(info.id);
                if (row == null) {
                    // Crear nueva fila solo si no existe
                    row = new JPanel(new BorderLayout(6,6));
                    JLabel name = new JLabel();
                    JProgressBar bar = new JProgressBar(0, 100);
                    bar.setStringPainted(true);
                    row.add(name, BorderLayout.WEST);
                    row.add(bar, BorderLayout.CENTER);
                    rowCache.put(info.id, row);
                }
                
                // Actualizar valores (sin recrear componentes)
                JLabel name = (JLabel) row.getComponent(0);
                JProgressBar bar = (JProgressBar) row.getComponent(1);
                name.setText(info.name + " (" + info.state + ")");
                int value = Math.min(100, 20 + (info.cpuPercent % 80));
                bar.setValue(value);
                bar.setString(info.cpuPercent + "%");
                
                listPanel.add(row);
            }
            
            // Solo revalidar si cambió el número de hilos
            if (listPanel.getComponentCount() != infos.size() || rowCache.size() != infos.size()) {
                listPanel.revalidate();
            }
            // El repaint del timer es suficiente, no necesitamos forzar aquí
        }
    }

    // Estructura ligera para transferir información de hilos a la UI
    static class ThreadInfoLite {
        final long id;
        final String name;
        final String state;
        final int cpuPercent; // porcentaje 0-100 estimado
        ThreadInfoLite(long id, String name, String state, int cpuPercent) {
            this.id = id; this.name = name; this.state = state; this.cpuPercent = cpuPercent;
        }
    }

    // Helper que consulta ThreadMXBean y mantiene estados previos para calcular
    // percent de CPU por hilo entre invocaciones
    static class ThreadMXBeanHelper {
        private static final ThreadMXBeanHelper INSTANCE = new ThreadMXBeanHelper();
        public static ThreadMXBeanHelper getInstance() { return INSTANCE; }

        private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        private final Map<Long, Long> prevCpu = new ConcurrentHashMap<>();
        private long lastTimeNanos = 0L;
        private final int processors = Runtime.getRuntime().availableProcessors();

        private ThreadMXBeanHelper() {
            // intentar activar la medición de tiempo de CPU si está soportado
            try {
                if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
                    threadBean.setThreadCpuTimeEnabled(true);
                }
            } catch (SecurityException ignored) {
                // en caso de restricciones de seguridad, ignorar
            }
        }

        // Obtener lista de hilos con estimación de uso CPU
        // FILTRADO: Solo muestra hilos relevantes del proyecto
        public java.util.List<ThreadInfoLite> getThreadInfos() {
            long now = System.nanoTime();
            long interval = (lastTimeNanos == 0L) ? 400_000_000L : Math.max(1, now - lastTimeNanos); // ns
            lastTimeNanos = now;

            java.util.List<ThreadInfoLite> out = new java.util.ArrayList<>();
            java.util.Set<Thread> threads = Thread.getAllStackTraces().keySet();

            boolean cpuSupported = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();

            for (Thread t : threads) {
                String name = t.getName();
                
                // FILTRO: Solo hilos relevantes del proyecto
                boolean isRelevant = false;
                
                // Hilos principales del proyecto
                if (name.equals("Model-Updater")) isRelevant = true;
                if (name.startsWith("Ball-Runner-")) isRelevant = true;
                if (name.equals("AWT-EventQueue-0")) isRelevant = true; // Hilo EDT de Swing
                if (name.startsWith("AWT-")) isRelevant = true; // Otros hilos de AWT
                if (name.equals("main")) isRelevant = true;
                
                // Hilos de auto-generación del controller
                if (name.toLowerCase().contains("auto")) isRelevant = true;
                if (name.toLowerCase().contains("timer")) isRelevant = true;
                
                // Si no es relevante, saltar
                if (!isRelevant) continue;
                
                long id = t.getId();
                String state = t.getState().toString();
                int percent = 0;
                if (cpuSupported) {
                    long cpu = threadBean.getThreadCpuTime(id); // ns o -1
                    long prev = prevCpu.getOrDefault(id, cpu);
                    long delta = (cpu >= 0 && prev >= 0) ? Math.max(0, cpu - prev) : 0L;
                    // convertir delta/interval a porcentaje en relación a núcleos
                    double p = (interval > 0) ? (double) delta / (double) interval * 100.0 / Math.max(1, processors) : 0.0;
                    percent = (int) Math.round(Math.max(0.0, Math.min(100.0, p)));
                    prevCpu.put(id, cpu >= 0 ? cpu : prev);
                } else {
                    percent = 0; // si no hay soporte, mostrar 0
                }
                out.add(new ThreadInfoLite(id, name, state, percent));
            }
            
            // Ordenar por nombre para mejor visualización
            out.sort((a, b) -> {
                // Primero Model-Updater, luego Ball-Runner, luego AWT, luego main
                if (a.name.equals("Model-Updater")) return -1;
                if (b.name.equals("Model-Updater")) return 1;
                if (a.name.startsWith("Ball-Runner-") && !b.name.startsWith("Ball-Runner-")) return -1;
                if (b.name.startsWith("Ball-Runner-") && !a.name.startsWith("Ball-Runner-")) return 1;
                return a.name.compareTo(b.name);
            });
            
            return out;
        }
    }

    /** Mueve el divisor para que el área izquierda (juego) tenga el ancho solicitado en píxeles. */
    public void setDividerForGameWidth(int gameWidth) {
        // Ejecutar en EDT porque manipulamos componentes Swing
        SwingUtilities.invokeLater(() -> {
            int total = split.getWidth();
            if (total <= 0) {
                // si aún no está renderizado, fijar la ubicación directamente
                split.setDividerLocation(gameWidth);
                return;
            }
            int max = Math.max(0, total - 50);
            int loc = Math.min(Math.max(0, gameWidth), max);
            split.setDividerLocation(loc);
        });
    }

    // Accesores a paneles
    public GamePanel getGamePanel() { return gamePanel; }
    public ControlPanel getControlPanel() { return controlPanel; }

    // Cuando el modelo cambia, pedir repintado y actualizar contador de bolas en la UI
    @Override
    public void modelChanged() {
        gamePanel.repaint();
        SwingUtilities.invokeLater(() -> {
            int count = model.getBalls().size();
            controlPanel.countLabel.setText("Bolas: " + count);
        });
    }

    /**
     * Panel donde se dibuja la simulación. Maneja su propio temporizador para repaint
     * y calcula métricas simples (FPS y tiempo de pintura).
     */
    public static class GamePanel extends JPanel {
        private final Model model;
        // seguimiento simple de FPS
        private volatile int fps = 0;
        private volatile long lastPaintMillis = 0; // tiempo que tardó el último paint en ms
        private int framesInInterval = 0; // número de frames contados en el intervalo actual
        private long intervalStart = System.currentTimeMillis(); // inicio del intervalo de medición
        // Flag para indicar visualmente si el panel tiene foco (para controles de jugador)
        private volatile boolean hasFocus = false;

        public GamePanel(Model model) {
            this.model = model;
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(model.getAreaWidth(), model.getAreaHeight()));
            setMinimumSize(new Dimension(model.getAreaWidth(), model.getAreaHeight()));
            setMaximumSize(new Dimension(model.getAreaWidth(), model.getAreaHeight()));
            
            // Optimización: activar doble buffering
            setDoubleBuffered(true);
            // Optimización: desactivar optimización de opacidad si no es necesario
            setOpaque(true);
            
            // Hacer el panel focusable para recibir eventos de teclado
            setFocusable(true);
            requestFocusInWindow();
            
            // Listener para detectar cuando gana/pierde foco
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    hasFocus = true;
                    repaint(); // Repintar para mostrar indicador
                }
                
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    hasFocus = false;
                    repaint(); // Repintar para quitar indicador
                }
            });
            
            // Listener para recuperar foco al hacer clic en el panel
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    requestFocusInWindow();
                }
            });
            
            // Agregar KeyListener para controlar la bola del jugador
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    PlayerBall player = model.getPlayerBall();
                    if (player != null) {
                        switch (e.getKeyCode()) {
                            case KeyEvent.VK_UP:
                            case KeyEvent.VK_W:
                                player.setUpPressed(true);
                                break;
                            case KeyEvent.VK_DOWN:
                            case KeyEvent.VK_S:
                                player.setDownPressed(true);
                                break;
                            case KeyEvent.VK_LEFT:
                            case KeyEvent.VK_A:
                                player.setLeftPressed(true);
                                break;
                            case KeyEvent.VK_RIGHT:
                            case KeyEvent.VK_D:
                                player.setRightPressed(true);
                                break;
                        }
                    }
                }
                
                @Override
                public void keyReleased(KeyEvent e) {
                    PlayerBall player = model.getPlayerBall();
                    if (player != null) {
                        switch (e.getKeyCode()) {
                            case KeyEvent.VK_UP:
                            case KeyEvent.VK_W:
                                player.setUpPressed(false);
                                break;
                            case KeyEvent.VK_DOWN:
                            case KeyEvent.VK_S:
                                player.setDownPressed(false);
                                break;
                            case KeyEvent.VK_LEFT:
                            case KeyEvent.VK_A:
                                player.setLeftPressed(false);
                                break;
                            case KeyEvent.VK_RIGHT:
                            case KeyEvent.VK_D:
                                player.setRightPressed(false);
                                break;
                        }
                    }
                }
            });

            // temporizador que solicita repintado aproximadamente cada 16 ms (~60Hz)
            Timer t = new Timer(16, e -> repaint());
            t.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            // medir tiempo de inicio en nanos para calcular duración del paint
            long paintStart = System.nanoTime();
            int w = getWidth();
            int h = getHeight();
            
            // Optimización: reutilizar buffer si es posible
            Graphics2D g2 = (Graphics2D) g;
            
            // Activar antialiasing solo si hay pocas bolas (< 500)
            List<Ball> balls = model.getBalls();
            if (balls.size() < 500) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            } else {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            }
            
            // Optimización: renderizado directo sin BufferedImage cuando hay muchas bolas
            // 1) Fondo
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);

            // 2) Area de juego (limitar a lo que define el model)
            int playW = Math.min(w, model.getAreaWidth());
            int playH = Math.min(h, model.getAreaHeight());
            g2.setColor(new Color(230, 230, 250));
            g2.fillRect(0, 0, playW, playH);
            g2.setColor(Color.GRAY);
            g2.drawRect(0, 0, Math.max(0, playW-1), Math.max(0, playH-1));

            // 3) Línea que marca la altura del área de juego (si diferente al panel)
            g2.setColor(Color.RED);
            int lineY = Math.min(playH, model.getAreaHeight());
            g2.drawLine(0, lineY, playW, lineY);

            // 4) Dibujar bolas: optimizado con culling y simplificación
            PlayerBall player = model.getPlayerBall();
            for (Ball b : balls) {
                int bx = b.getX();
                int by = b.getY();
                int r = b.getRadius();
                
                // Culling: omitir si está completamente fuera del área visible
                if (bx + r < 0 || bx - r > playW || by + r < 0 || by - r > playH) continue;
                
                g2.setColor(b.getColor());
                g2.fillOval(bx - r, by - r, r * 2, r * 2);
                
                // Dibujar borde especial para la bola del jugador
                if (b == player) {
                    // Borde grueso dorado para el jugador
                    g2.setColor(new Color(255, 215, 0)); // Gold
                    Stroke oldStroke = g2.getStroke();
                    g2.setStroke(new BasicStroke(3f));
                    g2.drawOval(bx - r, by - r, r * 2, r * 2);
                    g2.setStroke(oldStroke);
                    
                    // Indicador de dirección (pequeña flecha)
                    double vx = player.getVx();
                    double vy = player.getVy();
                    double speed = Math.sqrt(vx * vx + vy * vy);
                    if (speed > 0.01) {
                        int arrowLen = 15;
                        int arrowX = (int) (bx + (vx / speed) * arrowLen);
                        int arrowY = (int) (by + (vy / speed) * arrowLen);
                        g2.setColor(Color.YELLOW);
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawLine(bx, by, arrowX, arrowY);
                        g2.setStroke(oldStroke);
                    }
                } else if (balls.size() < 1000) {
                    // Omitir borde si hay muchas bolas para mejorar rendimiento
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawOval(bx - r, by - r, r * 2, r * 2);
                }
            }

            // 5) Recuadro central con indicador de ocupación
            int bx = model.getBoxX();
            int by = model.getBoxY();
            int bw = model.getBoxWidth();
            int bh = model.getBoxHeight();
            g2.setColor(new Color(245, 245, 220, 200));
            g2.fillRect(bx, by, bw, bh);
            int occCount = model.getBoxOccupants().size();
            int capacity = model.getBoxCapacity();
            // borde verde si hay espacio, rojo si está completo
            g2.setColor(occCount < capacity ? new Color(34,139,34) : new Color(178,34,34));
            Stroke old = g2.getStroke();
            g2.setStroke(new BasicStroke(3f));
            g2.drawRect(bx, by, bw, bh);
            g2.setStroke(old);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(String.format("Ocupación: %d / %d", occCount, capacity), bx + 6, by + 16);

            // 6) Indicador de foco (solo si hay jugador)
            if (player != null) {
                String focusMsg = hasFocus ? "[CONTROLES ACTIVOS]" : "[CLIC AQUI PARA CONTROLAR]";
                Color focusColor = hasFocus ? new Color(0, 200, 0) : new Color(255, 100, 0);
                
                g2.setColor(focusColor);
                g2.setFont(new Font("SansSerif", Font.BOLD, 14));
                FontMetrics fm = g2.getFontMetrics();
                int msgWidth = fm.stringWidth(focusMsg);
                int msgX = (playW - msgWidth) / 2;
                int msgY = 25;
                
                // Fondo semi-transparente
                g2.setColor(new Color(0, 0, 0, 150));
                g2.fillRoundRect(msgX - 5, msgY - 15, msgWidth + 10, 20, 10, 10);
                
                // Texto
                g2.setColor(focusColor);
                g2.drawString(focusMsg, msgX, msgY);
                
                // Si no tiene foco, parpadear
                if (!hasFocus && (System.currentTimeMillis() / 500) % 2 == 0) {
                    g2.setColor(new Color(255, 255, 0, 200));
                    g2.drawString(focusMsg, msgX, msgY);
                }
            }

            // 7) Cálculo simple de FPS por número de paints en el intervalo
            framesInInterval++;
            long now = System.currentTimeMillis();
            long elapsed = now - intervalStart;
            if (elapsed >= 250) {
                fps = (int) Math.round((framesInInterval * 1000.0) / Math.max(1, elapsed));
                framesInInterval = 0;
                intervalStart = now;
            }
            // tiempo del paint en ms (para diagnosticar render lento)
            lastPaintMillis = (System.nanoTime() - paintStart) / 1_000_000;
        }

        // Accesores para consultar métricas desde la UI
        public int getFps() { return fps; }
        public long getLastPaintMillis() { return lastPaintMillis; }
        
        /**
         * Método público para recuperar el foco del teclado.
         * Útil cuando se modifica configuración y se quiere volver a jugar.
         */
        public void recoverFocus() {
            requestFocusInWindow();
        }
    }

    /**
     * Panel con todos los controles de interacción: botones, spinners y pestañas.
     * Contiene subpaneles para configuración general, estadísticas y auto-generación.
     */
    public static class ControlPanel extends JPanel {
        // Botones de acción en la parte superior
        public final JButton addBtn = new JButton("Añadir bola");
        public final JButton removeBtn = new JButton("Quitar bola");
        public final JButton removeAllBtn = new JButton("Quitar todas");
        // Botón para pausar / continuar la simulación
        public final JButton pauseBtn = new JButton("Pausar");
        // Botón para añadir/quitar bola del jugador
        public final JButton playerBallBtn = new JButton("🎮 Añadir Jugador");
        // Spinners para tamaño del área
        public final JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(600, 50, 2000, 10));
        public final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(400, 50, 2000, 10));
        // Etiquetas de estado
        public final JLabel countLabel = new JLabel("Bolas: 0");
        public final JLabel fpsLabel = new JLabel("FPS: -");
        public final JLabel paintTimeLabel = new JLabel("Update ms: -");

        // Controles para auto-generación
        public final JCheckBox autoGenerateCheck = new JCheckBox("Auto-generar");
        public final JSpinner autoMinCount = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
        public final JSpinner autoMaxCount = new JSpinner(new SpinnerNumberModel(3, 1, 100, 1));
        public final JSpinner autoMinInterval = new JSpinner(new SpinnerNumberModel(200, 10, 10000, 50)); // ms
        public final JSpinner autoMaxInterval = new JSpinner(new SpinnerNumberModel(800, 10, 20000, 50)); // ms
        public final JSpinner sizeMinSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 200, 1));
        public final JSpinner sizeMaxSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 400, 1));

        // Controles para editar el recuadro manualmente
        public final JCheckBox explicitBoxCheck = new JCheckBox("Editar recuadro (coordenadas) ");
        public final JSpinner boxXSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 5000, 1));
        public final JSpinner boxYSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 5000, 1));
        public final JSpinner boxWSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 5000, 1));
        public final JSpinner boxHSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 5000, 1));
        public final JSpinner boxCapacitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        public final JButton resetBoxBtn = new JButton("Reset recuadro");

        public ControlPanel() {
            setLayout(new BorderLayout(6,6));
            setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

            // Parte superior: botones de acción en dos filas
            JPanel actionsContainer = new JPanel(new GridLayout(2, 1, 6, 6));
            
            // Primera fila de botones
            JPanel actions1 = new JPanel(new GridLayout(1,4,6,6));
            actions1.add(addBtn);
            actions1.add(removeBtn);
            actions1.add(removeAllBtn);
            actions1.add(pauseBtn);
            
            // Segunda fila: botón de jugador
            JPanel actions2 = new JPanel(new GridLayout(1,1,6,6));
            actions2.add(playerBallBtn);
            
            actionsContainer.add(actions1);
            actionsContainer.add(actions2);
            add(actionsContainer, BorderLayout.NORTH);

            // Centro: secciones apiladas con título (general, estadísticas, auto)
            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

            // Panel General (tamaño del área + contadores + edición de recuadro)
            JPanel general = new JPanel(new GridBagLayout());
            general.setBorder(BorderFactory.createTitledBorder("General"));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(6,6,6,6);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.gridx = 0; g.gridy = 0; general.add(new JLabel("Area width:"), g);
            g.gridx = 1; g.gridy = 0; general.add(widthSpinner, g);
            g.gridx = 0; g.gridy = 1; general.add(new JLabel("Area height:"), g);
            g.gridx = 1; g.gridy = 1; general.add(heightSpinner, g);
            g.gridx = 0; g.gridy = 2; general.add(new JLabel("Bolas:"), g);
            g.gridx = 1; g.gridy = 2; general.add(countLabel, g);

            // Controles de edición manual del recuadro
            g.gridx = 0; g.gridy = 3; g.gridwidth = 2; general.add(new JSeparator(), g); g.gridwidth = 1;
            g.gridx = 0; g.gridy = 4; g.gridwidth = 2; general.add(explicitBoxCheck, g); g.gridwidth = 1;
            g.gridx = 0; g.gridy = 5; general.add(new JLabel("Box X:"), g);
            g.gridx = 1; g.gridy = 5; general.add(boxXSpinner, g);
            g.gridx = 0; g.gridy = 6; general.add(new JLabel("Box Y:"), g);
            g.gridx = 1; g.gridy = 6; general.add(boxYSpinner, g);
            g.gridx = 0; g.gridy = 7; general.add(new JLabel("Box Width:"), g);
            g.gridx = 1; g.gridy = 7; general.add(boxWSpinner, g);
            g.gridx = 0; g.gridy = 8; general.add(new JLabel("Box Height:"), g);
            g.gridx = 1; g.gridy = 8; general.add(boxHSpinner, g);
            g.gridx = 0; g.gridy = 9; general.add(new JLabel("Capacidad:"), g);
            g.gridx = 1; g.gridy = 9; general.add(boxCapacitySpinner, g);
            g.gridx = 0; g.gridy = 10; g.gridwidth = 2; general.add(resetBoxBtn, g); g.gridwidth = 1;

            center.add(general);

            // Panel de estadísticas (FPS / tiempo de pintura)
            JPanel stats = new JPanel(new GridBagLayout());
            stats.setBorder(BorderFactory.createTitledBorder("Estadísticas"));
            GridBagConstraints s = new GridBagConstraints();
            s.insets = new Insets(4,6,4,6);
            s.anchor = GridBagConstraints.WEST;
            s.fill = GridBagConstraints.HORIZONTAL;
            s.gridx = 0; s.gridy = 0; stats.add(new JLabel("FPS:"), s);
            s.gridx = 1; s.gridy = 0; stats.add(fpsLabel, s);
            s.gridx = 0; s.gridy = 1; stats.add(new JLabel("Paint ms:"), s);
            s.gridx = 1; s.gridy = 1; stats.add(paintTimeLabel, s);
            center.add(Box.createVerticalStrut(6));
            center.add(stats);

            // Panel de auto-generación con sus controles
            JPanel auto = new JPanel(new GridBagLayout());
            auto.setBorder(BorderFactory.createTitledBorder("Auto-generación"));
            GridBagConstraints a = new GridBagConstraints();
            a.insets = new Insets(4,6,4,6);
            a.anchor = GridBagConstraints.WEST;
            a.fill = GridBagConstraints.HORIZONTAL;
            a.gridx = 0; a.gridy = 0; a.gridwidth = 2; auto.add(autoGenerateCheck, a);
            a.gridwidth = 1;
            a.gridx = 0; a.gridy = 1; auto.add(new JLabel("Bolas por spawn (min):"), a);
            a.gridx = 1; a.gridy = 1; auto.add(autoMinCount, a);
            a.gridx = 0; a.gridy = 2; auto.add(new JLabel("Bolas por spawn (max):"), a);
            a.gridx = 1; a.gridy = 2; auto.add(autoMaxCount, a);
            a.gridx = 0; a.gridy = 3; auto.add(new JLabel("Intervalo (ms) min:"), a);
            a.gridx = 1; a.gridy = 3; auto.add(autoMinInterval, a);
            a.gridx = 0; a.gridy = 4; auto.add(new JLabel("Intervalo (ms) max:"), a);
            a.gridx = 1; a.gridy = 4; auto.add(autoMaxInterval, a);
            a.gridx = 0; a.gridy = 5; auto.add(new JLabel("Tamaño min:"), a);
            a.gridx = 1; a.gridy = 5; auto.add(sizeMinSpinner, a);
            a.gridx = 0; a.gridy = 6; auto.add(new JLabel("Tamaño max:"), a);
            a.gridx = 1; a.gridy = 6; auto.add(sizeMaxSpinner, a);

            center.add(Box.createVerticalStrut(6));
            center.add(auto);

            // Scroll central y pestañas (configuración / hilos)
            JScrollPane centerScroll = new JScrollPane(center);
            centerScroll.setBorder(null);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Configuración", centerScroll);
            View.ThreadsPanel threadsPanel = new View.ThreadsPanel();
            tabs.addTab("Hilos", threadsPanel);
            add(tabs, BorderLayout.CENTER);

            // Pie con espacio flexible
            JPanel footer = new JPanel(new BorderLayout());
            footer.add(Box.createVerticalGlue(), BorderLayout.CENTER);
            add(footer, BorderLayout.SOUTH);
        }
    }

}
