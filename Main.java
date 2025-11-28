import javax.swing.SwingUtilities;
import model.Model;
import view.View;
import controller.Controller;

public class Main {
	public static void main(String[] args) {
		// Arranque de la aplicación en el Event Dispatch Thread de Swing.
		SwingUtilities.invokeLater(() -> {
			Model model = new Model();
			// Vista ligada al modelo
			View view = new View(model);
			// Controlador que enlaza modelo y vista
			Controller controller = new Controller(model, view);
			// Ajusta la posición del divider según el ancho inicial del área de juego
			view.setDividerForGameWidth(model.getAreaWidth());
			view.setVisible(true);
		});
	}
}
