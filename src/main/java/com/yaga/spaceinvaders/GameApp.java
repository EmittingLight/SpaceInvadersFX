package com.yaga.spaceinvaders;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class GameApp extends Application {

    // --- Размеры окна
    public static final int W = 540;
    public static final int H = 540;

    // --- Игровые параметры
    private static final Color GREEN = Color.LIMEGREEN;
    private static final double PLAYER_SPEED = 220;     // пикс/сек
    private static final double BULLET_SPEED = 380;
    private static final double ALIEN_BULLET_SPEED = 250;
    private static final int START_LIVES = 3;

    private GraphicsContext g;
    private long lastNs;
    private boolean left, right, shoot;

    // --- Сущности
    private Player player;
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Bullet> alienBullets = new ArrayList<>();
    private final List<Alien> aliens = new ArrayList<>();
    private double fleetDX = 40; // пикс/сек, направление по X
    private double fleetStepDown = 18;
    private double shootCooldown = 0;
    private int score = 0;
    private int lives = START_LIVES;
    private boolean gameOver = false;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(W, H);
        g = canvas.getGraphicsContext2D();

        Scene scene = new Scene(new StackPane(canvas), W, H, Color.BLACK);

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.A || e.getCode() == KeyCode.LEFT) left = true;
            if (e.getCode() == KeyCode.D || e.getCode() == KeyCode.RIGHT) right = true;
            if (e.getCode() == KeyCode.SPACE) shoot = true;
            if (e.getCode() == KeyCode.R && gameOver) resetGame();
        });
        scene.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.A || e.getCode() == KeyCode.LEFT) left = false;
            if (e.getCode() == KeyCode.D || e.getCode() == KeyCode.RIGHT) right = false;
            if (e.getCode() == KeyCode.SPACE) shoot = false;
        });

        stage.setTitle("Space Invaders FX — by Yaga");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        initGame();

        lastNs = System.nanoTime();
        new AnimationTimer() {
            @Override public void handle(long now) {
                double dt = (now - lastNs) / 1_000_000_000.0;
                lastNs = now;
                update(dt);
                render();
            }
        }.start();
    }

    private void initGame() {
        player = new Player(W / 2.0 - 15, H - 52, 30, 14);
        bullets.clear();
        alienBullets.clear();
        aliens.clear();
        score = 0;
        lives = START_LIVES;
        gameOver = false;

        // Матрица пришельцев 5x10
        int rows = 5, cols = 10;
        double startX = 60, startY = 70;
        double gapX = 36, gapY = 32;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double x = startX + c * gapX;
                double y = startY + r * gapY;
                aliens.add(new Alien(x, y, 24, 16));
            }
        }
        fleetDX = 40;
    }

    private void resetGame() {
        initGame();
    }

    // --- Игровая логика
    private void update(double dt) {
        if (gameOver) return;

        // Движение игрока
        double vx = 0;
        if (left)  vx -= PLAYER_SPEED;
        if (right) vx += PLAYER_SPEED;
        player.x += vx * dt;
        player.x = clamp(player.x, 8, W - player.w - 8);

        // Стрельба игрока
        shootCooldown -= dt;
        if (shoot && shootCooldown <= 0) {
            bullets.add(new Bullet(player.x + player.w / 2 - 1.5, player.y - 10, 3, 10, -BULLET_SPEED));
            shootCooldown = 0.35; // сек
        }

        // Обновление пуль
        bullets.forEach(b -> b.y += b.vy * dt);
        bullets.removeIf(b -> b.y + b.h < 0);

        alienBullets.forEach(b -> b.y += b.vy * dt);
        alienBullets.removeIf(b -> b.y > H);

        // Движение флота пришельцев
        if (!aliens.isEmpty()) {
            // ускоряемся по мере уменьшения флота
            double speed = Math.max(30, Math.min(160, 40 + (60 * (1.0 - aliens.size() / 50.0))));
            double dx = Math.signum(fleetDX) * speed * dt;

            // проверка границ
            boolean hitEdge = false;
            for (Alien a : aliens) {
                a.x += dx;
                if (a.x <= 8 || a.x + a.w >= W - 8) hitEdge = true;
            }
            if (hitEdge) {
                fleetDX = -fleetDX;
                for (Alien a : aliens) a.y += fleetStepDown;
            }
        }

        // Случайные выстрелы пришельцев (из нижних в колонке)
        if (!aliens.isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.9 * dt) {
            // выбираем случайную колонку и нижнего в ней
            int col = ThreadLocalRandom.current().nextInt(10);
            Alien shooter = bottomAlienOfColumn(col, 60, 36);
            if (shooter != null) {
                alienBullets.add(new Bullet(shooter.x + shooter.w / 2 - 1.5, shooter.y + shooter.h + 4, 3, 10, ALIEN_BULLET_SPEED));
            }
        }

        // Столкновения: пули игрока vs пришельцы
        Iterator<Bullet> itB = bullets.iterator();
        while (itB.hasNext()) {
            Bullet b = itB.next();
            Alien hit = firstHit(aliens, b);
            if (hit != null) {
                itB.remove();
                aliens.remove(hit);
                score += 10;
            }
        }

        // Столкновения: пули пришельцев vs игрок
        Iterator<Bullet> itAB = alienBullets.iterator();
        while (itAB.hasNext()) {
            Bullet b = itAB.next();
            if (player.intersects(b)) {
                itAB.remove();
                lives--;
                if (lives < 0) {
                    gameOver = true;
                } else {
                    // небольшой "респаун": центр и очистка пуль
                    player.x = W / 2.0 - player.w / 2.0;
                    bullets.clear();
                    alienBullets.clear();
                }
            }
        }

        // Проигрыш, если пришельцы опустились
        for (Alien a : aliens) {
            if (a.y + a.h >= H - 70) gameOver = true;
        }

        // Победа → новый уровень
        if (aliens.isEmpty()) {
            initGame();
            score += 50; // бонус за волну
        }
    }

    private Alien bottomAlienOfColumn(int col, double startX, double gapX) {
        Alien candidate = null;
        for (Alien a : aliens) {
            int c = (int) Math.round((a.x - startX) / gapX);
            if (c == col) {
                if (candidate == null || a.y > candidate.y) candidate = a;
            }
        }
        return candidate;
    }

    private Alien firstHit(List<Alien> list, Bullet b) {
        for (Alien a : list) if (a.intersects(b)) return a;
        return null;
    }

    // --- Рендер
    private void render() {
        // фон
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, W, H);

        // HUD
        g.setFill(GREEN);
        g.setFont(Font.font("Consolas", 22));
        g.fillText("SCORE " + score, 28, 36);
        g.fillText("LIVES", W - 180, 36);
        // иконки жизней
        for (int i = 0; i < Math.max(0, lives + 1); i++) drawShipIcon(W - 110 + i * 28, 20);

        // Игрок
        player.drawShip(g);

        // Пули и пришельцы
        g.setFill(GREEN);
        for (Bullet b : bullets) g.fillRect(b.x, b.y, b.w, b.h);
        for (Bullet b : alienBullets) g.fillRect(b.x, b.y, b.w, b.h);
        for (Alien a : aliens) a.drawAlien(g);

        if (gameOver) {
            g.setFill(GREEN);
            g.setFont(Font.font("Consolas", 34));
            g.fillText("GAME OVER", W / 2.0 - 120, H / 2.0 - 10);
            g.setFont(Font.font("Consolas", 18));
            g.fillText("Press R to restart", W / 2.0 - 95, H / 2.0 + 20);
        }
    }

    private void drawShipIcon(double x, double y) {
        g.setFill(GREEN);
        g.fillRect(x, y + 10, 20, 8);
        g.fillRect(x + 7, y + 6, 6, 4);
    }

    // --- Утилиты и классы
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public static class Entity {
        double x, y, w, h;
        public Entity(double x, double y, double w, double h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        boolean intersects(Entity o) {
            return x < o.x + o.w && x + w > o.x && y < o.y + o.h && y + h > o.y;
        }
    }

    public static class Player extends Entity {
        public Player(double x, double y, double w, double h) { super(x, y, w, h); }
        void drawShip(GraphicsContext g) {
            g.setFill(GREEN);
            // простая «танкетка»
            g.fillRect(x, y + h - 6, w, 6);
            g.fillRect(x + w/2 - 3, y, 6, h - 6);
        }
    }

    public static class Alien extends Entity {
        public Alien(double x, double y, double w, double h) { super(x, y, w, h); }
        void drawAlien(GraphicsContext g) {
            // пиксельный силуэт «пришельца»
            g.setFill(GREEN);
            // тело
            g.fillRect(x, y + 6, w, 6);
            g.fillRect(x + 2, y + 2, w - 4, 4);
            g.fillRect(x + 4, y + 12, w - 8, 4);
            // ножки
            g.fillRect(x + 2, y + 16, 6, 2);
            g.fillRect(x + w - 8, y + 16, 6, 2);
            // глазки
            g.clearRect(x + 6, y + 8, 4, 2);
            g.clearRect(x + w - 10, y + 8, 4, 2);
        }
    }

    public static class Bullet extends Entity {
        double vy;
        public Bullet(double x, double y, double w, double h, double vy) {
            super(x, y, w, h);
            this.vy = vy;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

