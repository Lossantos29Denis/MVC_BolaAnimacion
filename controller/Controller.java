package controller;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import model.Model;
import physics.Ball;
import view.View;

public class Controller {
	private final Model model;
	private final View view;
	private final AtomicBoolean autoRunning = new AtomicBoolean(false);
	private Thread autoThread;

	// Clase interna para manejar eventos del modelo
	// Recibe notificaciones del EventDetector y realiza acciones apropiadas
	public class EventManager {
		// Manejar colisión entre dos bolas
		public void handleBallCollision(Ball a, Ball b) {
			// Placeholder para lógica futura de respuesta a colisiones
			// Ejemplo: reproducir sonido, actualizar estadísticas, etc.
		}

		// Manejar eliminación de bola
		public void handleBallRemoved(Ball b) {
			// Placeholder para lógica futura
			// Ejemplo: actualizar contador, log, etc.
		}

		// Manejar entrada de bola al recuadro
		public void handleBallEntersBox(Ball b) {
			// Placeholder para lógica futura
			// Ejemplo: cambiar color de UI, mostrar notificación, etc.
		}

		// Manejar salida de bola del recuadro
		public void handleBallLeavesBox(Ball b) {
			// Placeholder para lógica futura
		}

		// Manejar pausa de simulación
		public void handlePaused() {
			// Placeholder para lógica futura
			// Ejemplo: actualizar botones, mostrar overlay, etc.
		}

		// Manejar reanudación de simulación
		public void handleResumed() {
			// Placeholder para lógica futura
		}
	}

	// Instancia del manejador de eventos
	private final EventManager eventManager = new EventManager();

	// Obtener el manejador de eventos
	public EventManager getEventManager() {
		return eventManager;
	}

	public Controller(Model model, View view) {
		this.model = model;
		this.view = view;

		// enlazar botones
		view.getControlPanel().addBtn.addActionListener(e -> {
			// usar el rango de tamaños para crear una bola con tamaño seleccionado
			int sizeMin = (Integer) view.getControlPanel().sizeMinSpinner.getValue();
			int sizeMax = (Integer) view.getControlPanel().sizeMaxSpinner.getValue();
			int r = sizeMin + ThreadLocalRandom.current().nextInt(Math.max(1, sizeMax - sizeMin + 1));
			model.addBallWithRadius(r);
			refreshCount();
		});

		view.getControlPanel().removeBtn.addActionListener(e -> {
			model.removeBall();
			refreshCount();
		});

		view.getControlPanel().removeAllBtn.addActionListener(e -> {
			model.removeAllBalls();
			refreshCount();
		});

		// botón de pausa/continuar: pausar la simulación globalmente
		view.getControlPanel().pauseBtn.addActionListener(e -> {
			View.ControlPanel cp = view.getControlPanel();
			if (model.isPaused()) {
				model.resume();
				cp.pauseBtn.setText("Pausar");
			} else {
				model.pause();
				cp.pauseBtn.setText("Continuar");
			}
		});
		
		// botón de jugador: añadir o quitar la bola controlable
		view.getControlPanel().playerBallBtn.addActionListener(e -> {
			View.ControlPanel cp = view.getControlPanel();
			if (model.hasPlayerBall()) {
				model.removePlayerBall();
				cp.playerBallBtn.setText("🎮 Añadir Jugador");
			} else {
				model.getOrCreatePlayerBall();
				cp.playerBallBtn.setText("🎮 Quitar Jugador");
				// Dar foco al panel de juego para que reciba eventos de teclado
				view.getGamePanel().requestFocusInWindow();
			}
			refreshCount();
		});

		view.getControlPanel().widthSpinner.addChangeListener(e -> {
			int w = (Integer) view.getControlPanel().widthSpinner.getValue();
			int h = (Integer) view.getControlPanel().heightSpinner.getValue();
			model.setAreaSize(w, h);
			view.getGamePanel().setPreferredSize(new java.awt.Dimension(w, h));
			view.getGamePanel().revalidate();
            view.setDividerForGameWidth(w);
            // Recuperar foco si hay jugador
            recoverFocusIfPlayerExists();
		});

		view.getControlPanel().heightSpinner.addChangeListener(e -> {
			int w = (Integer) view.getControlPanel().widthSpinner.getValue();
			int h = (Integer) view.getControlPanel().heightSpinner.getValue();
			model.setAreaSize(w, h);
			view.getGamePanel().setPreferredSize(new java.awt.Dimension(w, h));
			view.getGamePanel().revalidate();
			// Mantener el divisor en sincronía (si es necesario)
            view.setDividerForGameWidth(w);
            // Recuperar foco si hay jugador
            recoverFocusIfPlayerExists();
		});

		// contador inicial
		refreshCount();

		// actualización periódica de la UI para FPS y tiempo de paint
		Timer uiTimer = new Timer(250, ev -> {
			View.ControlPanel cp = view.getControlPanel();
			int fps = view.getGamePanel().getFps();
			long paintMs = view.getGamePanel().getLastPaintMillis();
			cp.fpsLabel.setText("FPS: " + fps);
			cp.paintTimeLabel.setText("Paint ms: " + paintMs);
		});
		uiTimer.start();

		// enlazar checkbox de auto-generación
		view.getControlPanel().autoGenerateCheck.addActionListener(e -> {
			boolean on = view.getControlPanel().autoGenerateCheck.isSelected();
			if (on) startAutoGenerator(); else stopAutoGenerator();
		});

	// --- Inicialización y enlace de edición del recuadro ---
	// inicializar spinner de capacidad
		view.getControlPanel().boxCapacitySpinner.setValue(model.getBoxCapacity());

	// inicializar spinners del recuadro desde el rect del modelo
		view.getControlPanel().boxXSpinner.setValue(model.getBoxX());
		view.getControlPanel().boxYSpinner.setValue(model.getBoxY());
		view.getControlPanel().boxWSpinner.setValue(model.getBoxWidth());
		view.getControlPanel().boxHSpinner.setValue(model.getBoxHeight());

	// inicializar checkbox explícito (desactivado por defecto)
		view.getControlPanel().explicitBoxCheck.setSelected(false);
		setBoxSpinnersEnabled(false);

		view.getControlPanel().explicitBoxCheck.addActionListener(ev -> {
			boolean explicit = view.getControlPanel().explicitBoxCheck.isSelected();
			setBoxSpinnersEnabled(explicit);
			if (!explicit) {
				model.resetBoxToRatio();
			}
		});

	// listeners de cambio para los spinners del recuadro
		ChangeListener boxChange = e -> {
			if (!view.getControlPanel().explicitBoxCheck.isSelected()) return;
			Integer x = toNullableInt(view.getControlPanel().boxXSpinner);
			Integer y = toNullableInt(view.getControlPanel().boxYSpinner);
			Integer w = toNullableInt(view.getControlPanel().boxWSpinner);
			Integer h = toNullableInt(view.getControlPanel().boxHSpinner);
			model.setBoxRect(x, y, w, h);
			// Recuperar foco si hay jugador
			recoverFocusIfPlayerExists();
		};
		view.getControlPanel().boxXSpinner.addChangeListener(boxChange);
		view.getControlPanel().boxYSpinner.addChangeListener(boxChange);
		view.getControlPanel().boxWSpinner.addChangeListener(boxChange);
		view.getControlPanel().boxHSpinner.addChangeListener(boxChange);

		view.getControlPanel().boxCapacitySpinner.addChangeListener(e -> {
			int cap = (Integer) view.getControlPanel().boxCapacitySpinner.getValue();
			model.setBoxCapacity(cap);
			// Recuperar foco si hay jugador
			recoverFocusIfPlayerExists();
		});

		view.getControlPanel().resetBoxBtn.addActionListener(e -> {
			model.resetBoxToRatio();
			view.getControlPanel().explicitBoxCheck.setSelected(false);
			setBoxSpinnersEnabled(false);
			// Recuperar foco si hay jugador
			recoverFocusIfPlayerExists();
		});
	}

	private void refreshCount() {
		SwingUtilities.invokeLater(() -> {
			int count = model.getBalls().size();
			String playerInfo = model.hasPlayerBall() ? " (incluye jugador)" : "";
			view.getControlPanel().countLabel.setText("Bolas: " + count + playerInfo);
			
			// Actualizar texto del botón del jugador
			if (model.hasPlayerBall()) {
				view.getControlPanel().playerBallBtn.setText("🎮 Quitar Jugador");
			} else {
				view.getControlPanel().playerBallBtn.setText("🎮 Añadir Jugador");
			}
		});
	}

	private void setBoxSpinnersEnabled(boolean on) {
		View.ControlPanel cp = view.getControlPanel();
		cp.boxXSpinner.setEnabled(on);
		cp.boxYSpinner.setEnabled(on);
		cp.boxWSpinner.setEnabled(on);
		cp.boxHSpinner.setEnabled(on);
		cp.boxCapacitySpinner.setEnabled(on);
	}

	private Integer toNullableInt(JSpinner s) {
		Object v = s.getValue();
		if (v instanceof Integer) return (Integer) v;
		return null;
	}

	private void startAutoGenerator() {
		if (autoRunning.get()) return;
		autoRunning.set(true);
		autoThread = new Thread(() -> {
			ThreadLocalRandom rnd = ThreadLocalRandom.current();
			while (autoRunning.get()) {
				try {
					int minCount = (Integer) view.getControlPanel().autoMinCount.getValue();
					int maxCount = (Integer) view.getControlPanel().autoMaxCount.getValue();
					int minInterval = (Integer) view.getControlPanel().autoMinInterval.getValue();
					int maxInterval = (Integer) view.getControlPanel().autoMaxInterval.getValue();
					int sizeMin = (Integer) view.getControlPanel().sizeMinSpinner.getValue();
					int sizeMax = (Integer) view.getControlPanel().sizeMaxSpinner.getValue();
					int toAdd = minCount + rnd.nextInt(Math.max(1, maxCount - minCount + 1));
					for (int i = 0; i < toAdd; i++) {
						// respetar la pausa del modelo: si está pausado, saltar la generación
						if (model.isPaused()) break;
						int r = sizeMin + rnd.nextInt(Math.max(1, sizeMax - sizeMin + 1));
						model.addBallWithRadius(r);
					}
					refreshCount();
					int sleep = minInterval + rnd.nextInt(Math.max(1, maxInterval - minInterval + 1));
					// durante la pausa el hilo puede dormir en intervalos más cortos y volver a comprobar
					int slept = 0;
					while (slept < sleep && !Thread.currentThread().isInterrupted()) {
						if (model.isPaused()) {
							Thread.sleep(100);
							slept += 100;
							continue;
						}
						int rem = Math.min(200, sleep - slept);
						Thread.sleep(rem);
						slept += rem;
					}
				} catch (InterruptedException ex) {
					break;
				}
			}
		}, "Auto-Generator");
		autoThread.setDaemon(true);
		autoThread.start();
	}

	private void stopAutoGenerator() {
		autoRunning.set(false);
		if (autoThread != null) autoThread.interrupt();
	}
	
	/**
	 * Recupera el foco del panel de juego si existe una bola de jugador.
	 * Esto permite que los controles de teclado funcionen después de
	 * modificar parámetros en el panel de control.
	 */
	private void recoverFocusIfPlayerExists() {
		if (model.hasPlayerBall()) {
			// Usar invokeLater para asegurar que se ejecuta después de
			// que se completen otras operaciones de la UI
			SwingUtilities.invokeLater(() -> {
				view.getGamePanel().recoverFocus();
			});
		}
	}
}
