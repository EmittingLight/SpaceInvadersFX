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
    private final List<Bunker> bunkers = new ArrayList<>();

    // Бонусный корабль (UFO)
    private BonusShip bonus;                 // null — нет на экране
    private double bonusSpawnTimer = 0;      // секунд до следующего появления

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
        bunkers.clear();
        bonus = null;
        bonusSpawnTimer = nextBonusDelay();
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

        // --- Укрытия (3 бункера по 6x3 кирпичиков)
        int cellsX = 6, cellsY = 3;
        double cellW = 18, cellH = 12;
        double bunkerW = cellsX * cellW;
        double bunkerH = cellsY * cellH;
        double y = H - 140;

        double[] xs = { W * 0.18, W * 0.48, W * 0.78 };
        for (double x : xs) {
            bunkers.add(new Bunker(x - bunkerW/2, y - bunkerH/2, cellsX, cellsY, cellW, cellH));
        }
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
            int col = ThreadLocalRandom.current().nextInt(10);
            Alien shooter = bottomAlienOfColumn(col, 60, 36);
            if (shooter != null) {
                alienBullets.add(new Bullet(shooter.x + shooter.w / 2 - 1.5, shooter.y + shooter.h + 4, 3, 10, ALIEN_BULLET_SPEED));
            }
        }

        // --- Бонусный корабль: спавн и движение
        bonusSpawnTimer -= dt;
        if (bonus == null && bonusSpawnTimer <= 0) {
            spawnBonus();
        }
        if (bonus != null) {
            bonus.x += bonus.vx * dt;
            if (bonus.x + bonus.w < -5 || bonus.x > W + 5) {
                bonus = null;
                bonusSpawnTimer = nextBonusDelay();
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

        // Пули игрока vs бункеры
        itB = bullets.iterator();
        while (itB.hasNext()) {
            Bullet b = itB.next();
            if (damageFirstHitBunker(b)) {
                itB.remove();
            }
        }

        // Пули игрока vs бонусный корабль
        if (bonus != null) {
            itB = bullets.iterator();
            boolean destroyed = false;
            while (itB.hasNext()) {
                Bullet b = itB.next();
                if (bonus.intersects(b)) {
                    itB.remove();
                    destroyed = true;
                    break;
                }
            }
            if (destroyed) {
                score += 100;     // награда
                bonus = null;
                bonusSpawnTimer = nextBonusDelay();
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

        // Пули пришельцев vs бункеры
        itAB = alienBullets.iterator();
        while (itAB.hasNext()) {
            Bullet b = itAB.next();
            if (damageFirstHitBunker(b)) {
                itAB.remove();
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

    private double nextBonusDelay() {
        // следующая попытка появления через 8–16 секунд
        return ThreadLocalRandom.current().nextDouble(8.0, 16.0);
    }

    private void spawnBonus() {
        // 50/50 слева направо или справа налево
        boolean fromLeft = ThreadLocalRandom.current().nextBoolean();
        double y = 42;
        double w = 34, h = 14;
        double speed = ThreadLocalRandom.current().nextDouble(120, 180); // пикс/сек
        double x = fromLeft ? -w - 4 : W + 4;
        double vx = fromLeft ? speed : -speed;
        bonus = new BonusShip(x, y, w, h, vx);
    }

    private boolean damageFirstHitBunker(Bullet b) {
        for (Bunker bun : bunkers) {
            if (bun.damage(b)) return true;
        }
        return false;
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

        // Бонусный корабль
        if (bonus != null) bonus.draw(g);

        // Бункеры
        for (Bunker bun : bunkers) bun.draw(g);

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
            g.setFill(GREEN);
            g.fillRect(x, y + 6, w, 6);
            g.fillRect(x + 2, y + 2, w - 4, 4);
            g.fillRect(x + 4, y + 12, w - 8, 4);
            g.fillRect(x + 2, y + 16, 6, 2);
            g.fillRect(x + w - 8, y + 16, 6, 2);
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

    public static class Bunker {
        double x, y;        // левый верх
        int cols, rows;     // размер сетки
        double cellW, cellH;
        boolean[][] alive;  // какие «кирпичики» ещё живы

        public Bunker(double x, double y, int cols, int rows, double cellW, double cellH) {
            this.x = x; this.y = y;
            this.cols = cols; this.rows = rows;
            this.cellW = cellW; this.cellH = cellH;
            this.alive = new boolean[rows][cols];
            for (int r = 0; r < rows; r++) Arrays.fill(alive[r], true);

            // чуть «скошенные» углы внизу
            if (rows >= 3 && cols >= 6) {
                alive[rows-1][0] = false;
                alive[rows-1][cols-1] = false;
            }
        }

        // Нанести урон пули; вернуть true, если пуля поглощена
        // Нанести урон; true — пуля поглощена бункером (кирпич сломан)
        boolean damage(Entity bullet) {
            // быстрый AABB по бункеру в целом
            double bw = cols * cellW, bh = rows * cellH;
            if (!(bullet.x < x + bw && bullet.x + bullet.w > x && bullet.y < y + bh && bullet.y + bullet.h > y))
                return false;

            // диапазон клеток, которых касается AABB пули
            int cMin = (int)Math.floor((bullet.x - x) / cellW);
            int cMax = (int)Math.floor((bullet.x + bullet.w - 1e-4 - x) / cellW);
            int rMin = (int)Math.floor((bullet.y - y) / cellH);
            int rMax = (int)Math.floor((bullet.y + bullet.h - 1e-4 - y) / cellH);

            cMin = Math.max(0, cMin); cMax = Math.min(cols - 1, cMax);
            rMin = Math.max(0, rMin); rMax = Math.min(rows - 1, rMax);

            // чуть уменьшим «жёсткость» зазора, чтобы попадания засчитывались честно
            final double GAP = 1.5; // было 2

            for (int r = rMin; r <= rMax; r++) {
                for (int c = cMin; c <= cMax; c++) {
                    if (!alive[r][c]) continue;

                    // фактический прямоугольник кирпича (с учётом зазора GAP)
                    double px = x + c * cellW;
                    double py = y + r * cellH;
                    double rw = cellW - GAP;
                    double rh = cellH - GAP;

                    // небольшая дельта, чтобы «скользящие» попадания учитывались
                    double eps = 0.0001;

                    if (bullet.x < px + rw + eps && bullet.x + bullet.w > px - eps &&
                            bullet.y < py + rh + eps && bullet.y + bullet.h > py - eps) {

                        alive[r][c] = false;

                        // «эрозия» вокруг попадания — опционально
                        if (Math.random() < 0.35) {
                            for (int dr = -1; dr <= 1; dr++) {
                                for (int dc = -1; dc <= 1; dc++) {
                                    int rr = r + dr, cc = c + dc;
                                    if (rr>=0 && rr<rows && cc>=0 && cc<cols && Math.random()<0.35) {
                                        alive[rr][cc] = false;
                                    }
                                }
                            }
                        }
                        return true; // кирпич сломан → пулю гасим
                    }
                }
            }

            // кирпич не задет → пулю НЕ гасим (пролетает через «дыру»)
            return false;
        }


        void draw(GraphicsContext g) {
            g.setFill(Color.LIMEGREEN);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (alive[r][c]) {
                        double px = x + c * cellW;
                        double py = y + r * cellH;
                        g.fillRect(px, py, cellW - 2, cellH - 2);
                    }
                }
            }
        }
    }

    public static class BonusShip extends Entity {
        double vx; // скорость по X
        public BonusShip(double x, double y, double w, double h, double vx) {
            super(x, y, w, h);
            this.vx = vx;
        }
        void draw(GraphicsContext g) {
            // простая «летающая тарелка»
            g.setFill(GREEN);
            g.fillRect(x, y + h/2, w, h/2);                 // корпус
            g.fillRect(x + w/2 - 6, y, 12, h/2);            // кабина
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
