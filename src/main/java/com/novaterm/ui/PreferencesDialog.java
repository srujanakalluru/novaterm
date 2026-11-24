package com.novaterm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.prefs.Preferences;

/**
 * Application preferences backed by java.util.prefs.Preferences
 * (stored in ~/Library/Preferences on macOS).
 */
public class PreferencesDialog extends Dialog<Void> {
    public static final String PREF_FONT_SIZE       = "terminal.fontSize";
    public static final String PREF_FONT_FAMILY     = "terminal.fontFamily";
    public static final String PREF_COLOR_SCHEME    = "terminal.colorScheme";
    public static final String PREF_SCROLLBACK      = "terminal.scrollback";
    public static final String PREF_CURSOR_BLINK    = "terminal.cursorBlink";
    public static final String PREF_CURSOR_STYLE    = "terminal.cursorStyle";  // block|beam|underline
    public static final String PREF_TRANSPARENCY    = "terminal.transparency";  // 0–100 (opacity %)
    public static final String PREF_BELL_MODE       = "terminal.bellMode";      // audio|visual|none
    public static final String PREF_SCROLL_SPEED    = "terminal.scrollSpeed";   // lines per wheel notch (1–10)
    public static final String PREF_LIGATURES       = "terminal.ligatures";     // boolean

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(PreferencesDialog.class);

    public static Preferences get() { return PREFS; }

    public PreferencesDialog() {
        setTitle("NovaTerm Preferences");
        setHeaderText(null);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(420);

        // Terminal settings
        Spinner<Integer> fontSizeSpinner = new Spinner<>(8, 32,
                PREFS.getInt(PREF_FONT_SIZE, 13));
        fontSizeSpinner.setEditable(true);

        ComboBox<String> fontCombo = new ComboBox<>();
        fontCombo.getItems().addAll(
                "SF Mono", "Menlo", "Monaco", "Courier New",
                "JetBrains Mono", "Fira Code", "Hack", "Inconsolata");
        fontCombo.setValue(PREFS.get(PREF_FONT_FAMILY, "SF Mono"));
        fontCombo.setEditable(true);

        ComboBox<String> schemeCombo = new ComboBox<>();
        schemeCombo.getItems().addAll(ColorScheme.names());
        schemeCombo.setValue(PREFS.get(PREF_COLOR_SCHEME, "Catppuccin Mocha"));

        // Color swatch showing bg + fg of selected scheme
        Rectangle bgSwatch = new Rectangle(18, 14);
        Rectangle fgSwatch = new Rectangle(18, 14);
        bgSwatch.setArcWidth(3); bgSwatch.setArcHeight(3);
        fgSwatch.setArcWidth(3); fgSwatch.setArcHeight(3);
        HBox swatch = new HBox(2, bgSwatch, fgSwatch);
        swatch.setStyle("-fx-alignment:center-left;");
        Runnable updateSwatch = () -> {
            String[] c = ColorScheme.resolve(schemeCombo.getValue());
            try { bgSwatch.setFill(Color.web(c[0])); } catch (Exception ignored) {}
            try { fgSwatch.setFill(Color.web(c[1])); } catch (Exception ignored) {}
        };
        updateSwatch.run();
        schemeCombo.valueProperty().addListener((obs, o, n) -> updateSwatch.run());
        HBox schemeRow = new HBox(6, schemeCombo, swatch);
        schemeRow.setStyle("-fx-alignment:center-left;");

        Spinner<Integer> scrollbackSpinner = new Spinner<>(100, 100000,
                PREFS.getInt(PREF_SCROLLBACK, 10000), 1000);
        scrollbackSpinner.setEditable(true);

        CheckBox cursorBlinkCheck = new CheckBox();
        cursorBlinkCheck.setSelected(PREFS.getBoolean(PREF_CURSOR_BLINK, true));

        ComboBox<String> cursorStyleCombo = new ComboBox<>();
        cursorStyleCombo.getItems().addAll("block", "beam", "underline");
        cursorStyleCombo.setValue(PREFS.get(PREF_CURSOR_STYLE, "block"));

        // Transparency: 0 = fully opaque, 100 = fully transparent
        Slider transparencySlider = new Slider(0, 80, PREFS.getInt(PREF_TRANSPARENCY, 0));
        transparencySlider.setBlockIncrement(5);
        transparencySlider.setShowTickLabels(true);
        transparencySlider.setShowTickMarks(true);
        transparencySlider.setMajorTickUnit(20);
        transparencySlider.setSnapToTicks(true);
        Label transparencyValueLbl = new Label(PREFS.getInt(PREF_TRANSPARENCY, 0) + "%");
        transparencySlider.valueProperty().addListener((obs, o, n) ->
                transparencyValueLbl.setText(n.intValue() + "%"));
        HBox transparencyRow = new HBox(8, transparencySlider, transparencyValueLbl);
        transparencyRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        GridPane termGrid = new GridPane();
        termGrid.setHgap(12); termGrid.setVgap(8);
        termGrid.setPadding(new Insets(8));
        termGrid.addRow(0, new Label("Font:"), fontCombo);
        termGrid.addRow(1, new Label("Font size:"), fontSizeSpinner);
        termGrid.addRow(2, new Label("Color scheme:"), schemeRow);
        termGrid.addRow(3, new Label("Scrollback lines:"), scrollbackSpinner);
        termGrid.addRow(4, new Label("Cursor style:"), cursorStyleCombo);
        termGrid.addRow(5, new Label("Blinking cursor:"), cursorBlinkCheck);
        termGrid.addRow(6, new Label("Transparency:"), transparencyRow);

        ComboBox<String> bellCombo = new ComboBox<>();
        bellCombo.getItems().addAll("audio", "visual", "none");
        bellCombo.setValue(PREFS.get(PREF_BELL_MODE, "audio"));
        termGrid.addRow(7, new Label("Bell:"), bellCombo);

        Spinner<Integer> scrollSpeedSpinner = new Spinner<>(1, 10,
                PREFS.getInt(PREF_SCROLL_SPEED, 3));
        scrollSpeedSpinner.setEditable(true);
        scrollSpeedSpinner.setPrefWidth(80);
        termGrid.addRow(8, new Label("Scroll speed:"), scrollSpeedSpinner);

        CheckBox ligaturesCheck = new CheckBox();
        ligaturesCheck.setSelected(PREFS.getBoolean(PREF_LIGATURES, false));
        termGrid.addRow(9, new Label("Font ligatures:"), ligaturesCheck);

        TitledPane termPane = new TitledPane("Terminal", termGrid);
        termPane.setCollapsible(false);

        // Connection settings
        Spinner<Integer> connectTimeoutSpinner = new Spinner<>(1, 120,
                PREFS.getInt(com.novaterm.ssh.ConnectionConfig.PREF_CONNECT_TIMEOUT, 10));
        connectTimeoutSpinner.setEditable(true);

        Spinner<Integer> authTimeoutSpinner = new Spinner<>(1, 120,
                PREFS.getInt(com.novaterm.ssh.ConnectionConfig.PREF_AUTH_TIMEOUT, 30));
        authTimeoutSpinner.setEditable(true);

        Spinner<Integer> keepAliveSpinner = new Spinner<>(0, 600,
                PREFS.getInt(com.novaterm.ssh.ConnectionConfig.PREF_KEEPALIVE_SECS, 60), 10);
        keepAliveSpinner.setEditable(true);

        Spinner<Integer> retryCountSpinner = new Spinner<>(0, 10,
                PREFS.getInt(com.novaterm.ssh.ConnectionConfig.PREF_RETRY_COUNT, 2));
        retryCountSpinner.setEditable(true);

        Spinner<Integer> retryDelaySpinner = new Spinner<>(1, 60,
                PREFS.getInt(com.novaterm.ssh.ConnectionConfig.PREF_RETRY_DELAY_SECS, 3));
        retryDelaySpinner.setEditable(true);

        GridPane connGrid = new GridPane();
        connGrid.setHgap(12); connGrid.setVgap(8);
        connGrid.setPadding(new Insets(8));
        connGrid.addRow(0, new Label("Connect timeout (s):"), connectTimeoutSpinner);
        connGrid.addRow(1, new Label("Auth timeout (s):"), authTimeoutSpinner);
        connGrid.addRow(2, new Label("Keep-alive interval (s):"), keepAliveSpinner);
        connGrid.addRow(3, new Label("Retry attempts:"), retryCountSpinner);
        connGrid.addRow(4, new Label("Retry delay (s):"), retryDelaySpinner);

        TitledPane connPane = new TitledPane("Connection", connGrid);
        connPane.setCollapsible(true);
        connPane.setExpanded(false);

        VBox content = new VBox(8, termPane, connPane);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                PREFS.putInt(PREF_FONT_SIZE, fontSizeSpinner.getValue());
                PREFS.put(PREF_FONT_FAMILY, fontCombo.getValue());
                PREFS.put(PREF_COLOR_SCHEME, schemeCombo.getValue());
                PREFS.putInt(PREF_SCROLLBACK, scrollbackSpinner.getValue());
                PREFS.putBoolean(PREF_CURSOR_BLINK, cursorBlinkCheck.isSelected());
                PREFS.put(PREF_CURSOR_STYLE, cursorStyleCombo.getValue());
                PREFS.putInt(PREF_TRANSPARENCY, (int) transparencySlider.getValue());
                PREFS.put(PREF_BELL_MODE, bellCombo.getValue());
                PREFS.putInt(PREF_SCROLL_SPEED, scrollSpeedSpinner.getValue());
                PREFS.putBoolean(PREF_LIGATURES, ligaturesCheck.isSelected());
                PREFS.putInt(com.novaterm.ssh.ConnectionConfig.PREF_CONNECT_TIMEOUT,
                        connectTimeoutSpinner.getValue());
                PREFS.putInt(com.novaterm.ssh.ConnectionConfig.PREF_AUTH_TIMEOUT,
                        authTimeoutSpinner.getValue());
                PREFS.putInt(com.novaterm.ssh.ConnectionConfig.PREF_KEEPALIVE_SECS,
                        keepAliveSpinner.getValue());
                PREFS.putInt(com.novaterm.ssh.ConnectionConfig.PREF_RETRY_COUNT,
                        retryCountSpinner.getValue());
                PREFS.putInt(com.novaterm.ssh.ConnectionConfig.PREF_RETRY_DELAY_SECS,
                        retryDelaySpinner.getValue());
                // Push font/color changes to all open terminal tabs
                TerminalTab.broadcastThemeReload();
            }
            return null;
        });
    }
}
