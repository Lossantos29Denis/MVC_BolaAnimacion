package model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import physics.Ball;
import physics.PlayerBall;

// Clase que mantiene el estado de la simulación: lista de bolas, geometría del área,
// recuadro central y el hilo que actualiza la física.
public class Model {
	// Lista concurrente (thread-safe) que contiene todas las bolas de la simulación.
	private final List<Ball> balls = new CopyOnWriteArrayList<>();
	// Bola controlable por el jugador (null si no existe)
	private volatile PlayerBall playerBall = null;
	// Tamaño del área de juego en píxeles (ancho y alto).
	private int areaWidth = 600;
	private int areaHeight = 400;

	// El recuadro central puede expresarse por proporción del área o por rectángulo explícito.
	// Si explicitBox* no son null, se usan esas coordenadas; si son null se usan las ratios.
	private double boxWidthRatio = 0.5; // fracción del ancho del área
	private double boxHeightRatio = 0.5; // fracción de la altura del área
	private Integer explicitBoxX = null; // coordenada X si se define explícitamente
	private Integer explicitBoxY = null; // coordenada Y si se define explícitamente
	private Integer explicitBoxWidth = null; // ancho si se define explícitamente
	private Integer explicitBoxHeight = null; // alto si se define explícitamente

	// Lista de ocupantes actuales dentro del recuadro (puede contener varias bolas).
	private final List<Ball> boxOccupants = new CopyOnWriteArrayList<>();
	// Capacidad máxima de ocupantes permitidos en el recuadro.
	private volatile int boxCapacity = 1;

	// Hilo que ejecuta el bucle de actualización de la simulación.
	private Thread updaterThread;
	// Flag que indica si el hilo actualizador debe seguir ejecutándose.
	private volatile boolean running = false;
	// Flag que indica si la simulación está pausada (si true, no se actualiza la física).
	private volatile boolean paused = false;
	// Tiempo que tardó la última actualización (nanosegundos) - útil para diagnóstico.
	private volatile long lastUpdateNanos = 0L;

	// OBJETO MONITOR para sincronización con notifyAll()
	// Todos los hilos esperan aquí cuando la simulación está pausada
	private final Object pauseMonitor = new Object();

	// Interfaz mínima para listeners que deseen ser notificados de cambios del modelo.
	public interface ModelListener {
		void modelChanged();
	}

	// Clase interna para detectar y notificar eventos de la simulación
	// Detecta colisiones, entradas/salidas del recuadro, eliminación de bolas, pausas, etc.
	public class EventDetector {
		// Evento: dos bolas colisionan
		public void onBallCollision(Ball a, Ball b) {
			// Notificar a listeners si se implementa en el futuro
			// Por ahora solo placeholder para arquitectura de eventos
		}

		// Evento: una bola fue eliminada
		public void onBallRemoved(Ball b) {
			// Placeholder para notificar a listeners específicos de eventos
		}

		// Evento: una bola entra en el recuadro central
		public void onBallEntersBox(Ball b) {
			// Placeholder para arquitectura de eventos
		}

		// Evento: una bola sale del recuadro central
		public void onBallLeavesBox(Ball b) {
			// Placeholder para arquitectura de eventos
		}

		// Evento: la simulación se pausa
		public void onPaused() {
			// Placeholder para arquitectura de eventos
		}

		// Evento: la simulación se reanuda
		public void onResumed() {
			// Placeholder para arquitectura de eventos
		}
	}

	// Instancia del detector de eventos
	private final EventDetector eventDetector = new EventDetector();

	// Obtener el detector de eventos para registro externo
	public EventDetector getEventDetector() {
		return eventDetector;
	}

	// Devolver el último tiempo de actualización (ns) para métricas.
	public long getLastUpdateNanos() {
		return lastUpdateNanos;
	}

	// Lista de listeners registrados (thread-safe).
	private final List<ModelListener> listeners = new CopyOnWriteArrayList<>();

	// Registrar un listener que se llamará cuando cambie el modelo.
	public void addListener(ModelListener l) {
		listeners.add(l);
	}

	// Eliminar un listener previamente registrado.
	public void removeListener(ModelListener l) {
		listeners.remove(l);
	}

	// Notificar a todos los listeners. Siempre invocamos desde el EDT de Swing
	// para mantener la seguridad de la interfaz gráfica (no llamar a métodos de Swing desde hilos externos).
	private void notifyListeners() {
		if (SwingUtilities.isEventDispatchThread()) {
			for (ModelListener l : listeners) l.modelChanged();
		} else {
			SwingUtilities.invokeLater(() -> {
				for (ModelListener l : listeners) l.modelChanged();
			});
		}
	}

	// Obtener dimensiones del área
	public int getAreaWidth() { return areaWidth; }
	public int getAreaHeight() { return areaHeight; }

	// Ajustar el tamaño mínimo del área y notificar a la vista
	public void setAreaSize(int w, int h) {
		this.areaWidth = Math.max(50, w); // evitar valores absurdamente pequeños
		this.areaHeight = Math.max(50, h);
		notifyListeners();
	}

	// Añadir una bola con radio aleatorio
	public void addBall() {
		Ball b = new Ball(this);
		balls.add(b);
		notifyListeners();
		startUpdaterIfNeeded(); // asegurar que el hilo de actualización se ejecute
	}

	// Añadir bola con radio especificado
	public void addBallWithRadius(int radius) {
		Ball b = new Ball(this, radius);
		balls.add(b);
		notifyListeners();
		startUpdaterIfNeeded();
	}

	// Añadir varias bolas con radios dentro del rango
	public void addBalls(int count, int minRadius, int maxRadius) {
		if (count <= 0) return;
		for (int i = 0; i < count; i++) {
			int r = minRadius + (int) (Math.random() * (Math.max(0, maxRadius - minRadius + 1)));
			balls.add(new Ball(this, r));
		}
		notifyListeners();
		startUpdaterIfNeeded();
	}

	// Quitar la última bola añadida (si existe)
	public void removeBall() {
		if (!balls.isEmpty()) {
			balls.remove(balls.size() - 1);
			notifyListeners();
		}
	}

	// Exponer la lista de bolas (la lista es concurrente)
	public List<Ball> getBalls() { return balls; }

	// Geometría del recuadro central: si existen valores explícitos se usan,
	// en caso contrario se calculan a partir de las ratios y el tamaño del área.
	public int getBoxWidth() {
		if (explicitBoxWidth != null) return explicitBoxWidth;
		return (int) Math.max(10, Math.round(areaWidth * boxWidthRatio));
	}

	public int getBoxHeight() {
		if (explicitBoxHeight != null) return explicitBoxHeight;
		return (int) Math.max(10, Math.round(areaHeight * boxHeightRatio));
	}

	public int getBoxX() {
		if (explicitBoxX != null) return explicitBoxX;
		return (areaWidth - getBoxWidth())/2; // centrar horizontalmente
	}

	public int getBoxY() {
		if (explicitBoxY != null) return explicitBoxY;
		return (areaHeight - getBoxHeight())/2; // centrar verticalmente
	}

	public List<Ball> getBoxOccupants() { return boxOccupants; }
	public int getBoxCapacity() { return boxCapacity; }

	// Definir la capacidad máxima de ocupantes y recortar la lista si es necesario
	public void setBoxCapacity(int capacity) {
		this.boxCapacity = Math.max(1, capacity);
		while (boxOccupants.size() > this.boxCapacity) {
			boxOccupants.remove(boxOccupants.size() - 1);
		}
		notifyListeners();
	}

	// Establecer rect del recuadro explícitamente (o null para volver a ratios)
	public void setBoxRect(Integer x, Integer y, Integer w, Integer h) {
		this.explicitBoxX = x;
		this.explicitBoxY = y;
		this.explicitBoxWidth = w;
		this.explicitBoxHeight = h;
		notifyListeners();
	}

	// Volver a usar ratios en lugar de rect explícito
	public void resetBoxToRatio() {
		this.explicitBoxX = null;
		this.explicitBoxY = null;
		this.explicitBoxWidth = null;
		this.explicitBoxHeight = null;
		notifyListeners();
	}

	// Eliminar todas las bolas y detener el actualizador
	public void removeAllBalls() {
		balls.clear();
		playerBall = null; // también eliminar la bola del jugador
		notifyListeners();
		stopUpdater();
	}
	
	// Crear/obtener la bola del jugador
	public PlayerBall getOrCreatePlayerBall() {
		if (playerBall == null) {
			playerBall = new PlayerBall(this);
			balls.add(playerBall); // añadir a la lista de bolas
			startUpdaterIfNeeded();
			notifyListeners();
		}
		return playerBall;
	}
	
	// Eliminar la bola del jugador
	public void removePlayerBall() {
		if (playerBall != null) {
			balls.remove(playerBall);
			playerBall = null;
			notifyListeners();
		}
	}
	
	// Verificar si existe la bola del jugador
	public boolean hasPlayerBall() {
		return playerBall != null;
	}
	
	// Obtener la bola del jugador (puede ser null)
	public PlayerBall getPlayerBall() {
		return playerBall;
	}

	// Iniciar el hilo encargado de actualizar la simulación si no está ya en marcha.
	private void startUpdaterIfNeeded() {
		if (running) return;
		running = true;
		updaterThread = new Thread(() -> {
			final double dt = 16.0; // milisegundos por tick (~60Hz)
			while (running) {
				long start = System.nanoTime();

				// NOTIFYALL: Esperar si está pausado
				synchronized (pauseMonitor) {
					while (paused && running) {
						try {
							pauseMonitor.wait();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							break;
						}
					}
				}

				// Si el modelo está en pausa, saltar la actualización física
				if (!paused) {
					updateAll(dt);
				}
				// Notificar listeners (en EDT mediante notifyListeners)
				notifyListeners();
				long took = System.nanoTime() - start; // tiempo de la iteración en ns
				lastUpdateNanos = took;
				long sleepMs = Math.max(1, (long) dt - took/1_000_000);
				try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
			}
		}, "Model-Updater");
		updaterThread.setDaemon(true);
		updaterThread.start();
	}

	// Pausar la simulación (no se ejecutará updateAll mientras está pausada)
	public void pause() {
		this.paused = true;
		notifyListeners();
	}

	// Reanudar la simulación y asegurar el hilo actualizador
	public void resume() {
		this.paused = false;
		startUpdaterIfNeeded();

		// NOTIFYALL: Despertar todos los hilos que esperan
		synchronized (pauseMonitor) {
			pauseMonitor.notifyAll();
		}

		notifyListeners();
	}

	// Alternar pausa y devolver el nuevo estado
	public boolean togglePaused() {
		if (this.paused) resume(); else pause();
		return this.paused;
	}

	public boolean isPaused() { return this.paused; }

	// NOTIFYALL: Obtener el monitor de pausa para sincronización externa
	public Object getPauseMonitor() {
		return pauseMonitor;
	}

	// Detener el hilo actualizador
	private void stopUpdater() {
		running = false;
		if (updaterThread != null) updaterThread.interrupt();
	}

	// Método central que actualiza la física: movimiento, colisiones, ocupación del recuadro, etc.
	// dt debe estar en milisegundos
	private void updateAll(double dt) {
		final int n = balls.size();
		
		// Optimización: cachear objetos Ball en array local para evitar accesos a CopyOnWriteArrayList
		final Ball[] ballArray = balls.toArray(new Ball[n]);
		
		// 1) Mover todas las bolas y procesar colisiones con paredes en un solo bucle
		final int areaW = this.areaWidth;
		final int areaH = this.areaHeight;
		
		for (Ball b : ballArray) {
			// Mover la bola
			b.move(dt);
			
			// Colisiones con las paredes (inline para evitar llamadas múltiples)
			double bxpos = b.getXDouble();
			double bypos = b.getYDouble();
			int radius = b.getRadius();
			
			// Optimización: reducir llamadas a getters/setters
			if (bxpos - radius < 0) { 
				b.setXDouble(radius); 
				b.setVx(-b.getVx()); 
			} else if (bxpos + radius > areaW) { 
				b.setXDouble(areaW - radius); 
				b.setVx(-b.getVx()); 
			}
			
			if (bypos - radius < 0) { 
				b.setYDouble(radius); 
				b.setVy(-b.getVy()); 
			} else if (bypos + radius > areaH) { 
				b.setYDouble(areaH - radius); 
				b.setVy(-b.getVy()); 
			}
		}

		// 2) Lógica del recuadro central (optimizada)
		final int bx = getBoxX();
		final int by = getBoxY();
		final int bw = getBoxWidth();
		final int bh = getBoxHeight();
		final int bxMax = bx + bw;
		final int byMax = by + bh;

		// Limpiar ocupantes que han salido (usar iterator para evitar ConcurrentModificationException)
		boxOccupants.removeIf(occ -> {
			int cx = occ.getX();
			int cy = occ.getY();
			return !(cx >= bx && cx <= bxMax && cy >= by && cy <= byMax);
		});

		// Procesar entrada al recuadro (optimizado)
		final int currentOccupants = boxOccupants.size();
		final boolean hasCapacity = currentOccupants < boxCapacity;
		
		for (Ball b : ballArray) {
			if (boxOccupants.contains(b)) continue;

			int cx = b.getX();
			int cy = b.getY();

			if (hasCapacity && cx >= bx && cx <= bxMax && cy >= by && cy <= byMax) {
				boxOccupants.add(b);
				continue;
			}

			// Rebotar si el recuadro está lleno
			if (!hasCapacity) {
				double bxd = b.getXDouble();
				double byd = b.getYDouble();
				int r = b.getRadius();
				
				boolean intersectsX = bxd + r > bx && bxd - r < bxMax;
				boolean intersectsY = byd + r > by && byd - r < byMax;
				
				if (intersectsX && intersectsY) {
					double leftPen = bxd + r - bx;
					double rightPen = bxMax - (bxd - r);
					double topPen = byd + r - by;
					double bottomPen = byMax - (byd - r);
					double minPen = Math.min(Math.min(leftPen, rightPen), Math.min(topPen, bottomPen));
					
					if (minPen == leftPen) {
						b.setXDouble(bx - r);
						b.setVx(-Math.abs(b.getVx()));
					} else if (minPen == rightPen) {
						b.setXDouble(bxMax + r);
						b.setVx(Math.abs(b.getVx()));
					} else if (minPen == topPen) {
						b.setYDouble(by - r);
						b.setVy(-Math.abs(b.getVy()));
					} else {
						b.setYDouble(byMax + r);
						b.setVy(Math.abs(b.getVy()));
					}
				}
			}
		}

		// 3) Colisiones entre bolas - OPTIMIZACION CRITICA: Spatial Partitioning
		// Para evitar O(n²) completo, usar grid espacial simple
		processBallCollisions(ballArray);

		// 4) Eliminar bolas que superen el umbral de impactos (optimizado)
		// PERO: nunca eliminar la bola del jugador
		balls.removeIf(b -> b.hitCount >= 5 && b != playerBall);

		// 5) Limpiar ocupantes del recuadro (quitar eliminadas) - optimizado
		boxOccupants.removeIf(occ -> !balls.contains(occ));

		// 6) Si no quedan bolas (excepto el jugador), parar el hilo actualizador para ahorrar CPU
		if (balls.isEmpty() || (balls.size() == 1 && playerBall != null)) {
			// Solo queda el jugador o no hay bolas, pero mantener updater si hay jugador
			if (playerBall == null) {
				stopUpdater();
			}
		}
	}
	
	/**
	 * Procesa colisiones entre bolas usando spatial partitioning (grid)
	 * para reducir complejidad de O(n²) a aproximadamente O(n) en casos típicos
	 */
	private void processBallCollisions(Ball[] ballArray) {
		if (ballArray.length < 2) return;
		
		// Tamaño de celda del grid: usar el radio máximo esperado * 2
		final int cellSize = 40; // ajustable según radio típico de bolas
		final int gridCols = (areaWidth / cellSize) + 1;
		final int gridRows = (areaHeight / cellSize) + 1;
		
		// Grid espacial: lista de bolas por celda
		@SuppressWarnings("unchecked")
		java.util.List<Ball>[][] grid = new java.util.List[gridRows][gridCols];
		
		// Asignar bolas a celdas del grid
		for (Ball b : ballArray) {
			int gridX = Math.max(0, Math.min(gridCols - 1, (int)(b.getXDouble() / cellSize)));
			int gridY = Math.max(0, Math.min(gridRows - 1, (int)(b.getYDouble() / cellSize)));
			
			if (grid[gridY][gridX] == null) {
				grid[gridY][gridX] = new java.util.ArrayList<>(4);
			}
			grid[gridY][gridX].add(b);
		}
		
		// Procesar colisiones solo entre bolas en celdas adyacentes
		for (int gy = 0; gy < gridRows; gy++) {
			for (int gx = 0; gx < gridCols; gx++) {
				java.util.List<Ball> cellBalls = grid[gy][gx];
				if (cellBalls == null || cellBalls.isEmpty()) continue;
				
				// Colisiones dentro de la misma celda
				for (int i = 0; i < cellBalls.size(); i++) {
					for (int j = i + 1; j < cellBalls.size(); j++) {
						checkAndResolveCollision(cellBalls.get(i), cellBalls.get(j));
					}
				}
				
				// Colisiones con celdas adyacentes (derecha, abajo, diagonal abajo-derecha, diagonal abajo-izquierda)
				// Solo revisar la mitad para evitar duplicados
				if (gx + 1 < gridCols && grid[gy][gx + 1] != null) {
					for (Ball a : cellBalls) {
						for (Ball b : grid[gy][gx + 1]) {
							checkAndResolveCollision(a, b);
						}
					}
				}
				if (gy + 1 < gridRows && grid[gy + 1][gx] != null) {
					for (Ball a : cellBalls) {
						for (Ball b : grid[gy + 1][gx]) {
							checkAndResolveCollision(a, b);
						}
					}
				}
				if (gx + 1 < gridCols && gy + 1 < gridRows && grid[gy + 1][gx + 1] != null) {
					for (Ball a : cellBalls) {
						for (Ball b : grid[gy + 1][gx + 1]) {
							checkAndResolveCollision(a, b);
						}
					}
				}
				if (gx > 0 && gy + 1 < gridRows && grid[gy + 1][gx - 1] != null) {
					for (Ball a : cellBalls) {
						for (Ball b : grid[gy + 1][gx - 1]) {
							checkAndResolveCollision(a, b);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Verifica y resuelve colisión entre dos bolas (método extraído para reutilización)
	 */
	private void checkAndResolveCollision(Ball a, Ball b) {
		double dx = b.getXDouble() - a.getXDouble();
		double dy = b.getYDouble() - a.getYDouble();
		double dist2 = dx*dx + dy*dy;
		double minDist = a.getRadius() + b.getRadius();
		double minDist2 = minDist * minDist;
		
		if (dist2 <= minDist2 && dist2 > 0.001) {
			double dist = Math.sqrt(dist2);
			// Vector normal de colisión
			double nx = dx / dist;
			double ny = dy / dist;
			
			// Velocidad relativa
			double rvx = b.getVx() - a.getVx();
			double rvy = b.getVy() - a.getVy();
			double relVelAlongNormal = rvx*nx + rvy*ny;
			
			// Si se están separando, ignorar
			if (relVelAlongNormal > 0) return;
			
			// Impulso elástico (simplificado para masas iguales)
			double impulse = -relVelAlongNormal;
			double ix = impulse * nx;
			double iy = impulse * ny;
			
			// Aplicar cambio de velocidad
			a.setVx(a.getVx() - ix);
			a.setVy(a.getVy() - iy);
			b.setVx(b.getVx() + ix);
			b.setVy(b.getVy() + iy);
			
			// Corrección posicional
			double overlap = minDist - dist;
			double correction = overlap * 0.5 + 0.1;
			a.setXDouble(a.getXDouble() - nx * correction);
			a.setYDouble(a.getYDouble() - ny * correction);
			b.setXDouble(b.getXDouble() + nx * correction);
			b.setYDouble(b.getYDouble() + ny * correction);
			
			// Incrementar contador de colisiones
			a.hitCount++;
			b.hitCount++;
		}
	}
}
