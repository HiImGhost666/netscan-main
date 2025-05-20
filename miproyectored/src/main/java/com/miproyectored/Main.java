package com.miproyectored;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class Main extends Application {
    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/miproyectored/view/dashboard.fxml"));
        Parent root = loader.load();

        primaryStage.setTitle("NetScan");
        primaryStage.getIcons().add(
                new Image(getClass().getResourceAsStream("/com/miproyectored/images/gato.jpg"))
        );
        primaryStage.setScene(new Scene(root, 900, 700));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}