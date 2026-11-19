package game.model;

import java.awt.Color;

public class Player {
    public final String id;
    public final Color color;
    public int score = 0;

    public Player(String id, Color color) {
        this.id = id;
        this.color = color;
    }
}