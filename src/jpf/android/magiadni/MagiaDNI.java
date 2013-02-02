//  MagiaDNI - Calcular dígito de control de los datos OCR del DNI
//  Copyright © 2011-2013  Josep Portella Florit <hola@josep-portella.com>
//
//  This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jpf.android.magiadni;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashMap;
import java.util.List;

public class MagiaDNI extends Activity {
    private MenuItem opciónManual;
    private MenuItem opciónCopyleft;
    private MenuItem opciónDepuración;
    private Pantalla pantalla;
    private Preview preview;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        DatosOCR datosOCR = new DatosOCR(this);
        ReentrantLock lock = new ReentrantLock();
        pantalla = new Pantalla(this, datosOCR, lock);
        preview = new Preview(this, datosOCR, pantalla, lock);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(preview);
        addContentView(pantalla, new LayoutParams(LayoutParams.WRAP_CONTENT,
                                                  LayoutParams.WRAP_CONTENT));
        AlertDialog deleteAlert = new AlertDialog.Builder(this).create();
        deleteAlert.setTitle("MagiaDNI");
        deleteAlert.setMessage(
            "Por favor, lee el manual antes de usar la aplicación.");
        deleteAlert.setButton("Leer manual", new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mostrarTexto("manual");
            }
        });
        deleteAlert.setButton2("Continuar", new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        deleteAlert.show();
        }

    public boolean onCreateOptionsMenu(Menu menu) {
        opciónManual = menu.add("Manual");
        opciónCopyleft = menu.add("Copyleft");
        opciónDepuración = menu.add("Depuración");
        return super.onCreateOptionsMenu(menu);
    }

    private void mostrarTexto(String archivo) {
        Intent intent = new Intent(this, Texto.class);
        Bundle bundle = new Bundle();
        bundle.putString("archivo", archivo);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (item == opciónManual)
            mostrarTexto("manual");
        if (item == opciónCopyleft)
            mostrarTexto("copyleft");
        if (item == opciónDepuración)
            pantalla.cambiaDepuración();
        return false;
    }
}

class Preview extends SurfaceView implements SurfaceHolder.Callback,
                                             Camera.PreviewCallback,
                                             Camera.AutoFocusCallback {
    private static final int LÍMITE_ERRORES = 20;

    private ReentrantLock lock;
    private Camera camera;
    private DatosOCR datosOCR;
    private Pantalla pantalla;
    private Display display;
    private boolean enfocando;
    private int errores;

    Preview(Context context, DatosOCR datosOCR, Pantalla pantalla,
            ReentrantLock lock) {
        super(context);
        this.datosOCR = datosOCR;
        this.pantalla = pantalla;
        this.lock = lock;
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        display = ((WindowManager) context.getSystemService(
            Context.WINDOW_SERVICE)).getDefaultDisplay();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        camera = Camera.open();
        try {
            Camera.Parameters parameters = camera.getParameters();
            Method getSupportedPreviewSizes =
                Camera.Parameters.class.getMethod(
                    "getSupportedPreviewSizes");
            @SuppressWarnings("unchecked")
            List<Camera.Size> sizes = (List<Camera.Size>)
                getSupportedPreviewSizes.invoke(parameters);
            int displayRatio = (int) (((float) display.getWidth()
                                       / display.getHeight())
                                      * 100);
            Camera.Size max = null;
            Camera.Size maxÓptimo = null;
            for (Camera.Size size : sizes) {
                if (max == null || max.width <= size.width)
                    max = size;
                int ratio = (int) (((float) size.width / size.height) * 100);
                if (ratio == displayRatio
                    && (maxÓptimo == null || maxÓptimo.width < size.width))
                    maxÓptimo = size;
            }
            if (maxÓptimo == null)
                parameters.setPreviewSize(max.width, max.height);
            else
                parameters.setPreviewSize(maxÓptimo.width, maxÓptimo.height);
            camera.setParameters(parameters);
        } catch (NoSuchMethodException e) {
        } catch (InvocationTargetException e) {
        } catch (IllegalAccessException e) {
        }
        Camera.Size size = camera.getParameters().getPreviewSize();
        datosOCR.setTamañoImagen(size.width, size.height);
        errores = LÍMITE_ERRORES;
        enfocando = false;
        try {
            camera.setPreviewDisplay(holder);
        } catch (IOException e) {
        }
    }

    public void onPreviewFrame(byte[] data, Camera _) {
        if (lock.tryLock()) {
            if (datosOCR.encontrar(data))
                errores = 0;
            else if (!enfocando && ++errores > LÍMITE_ERRORES) {
                errores = 0;
                enfocando = true;
                camera.autoFocus(this);
            }
            pantalla.invalidate();
        }
    }

    public void onAutoFocus(boolean success, Camera _) {
        enfocando = false;
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    public void surfaceChanged(SurfaceHolder holder, int format,
                               int width, int height) {
        camera.startPreview();
        camera.setPreviewCallback(this);
    }
}

class Pantalla extends View {
    private static final int INICIO_CUENTA_ATRÁS = 20;
    private static final int CONFIRMACIÓN_ACEPTABLE = 1;
    private static final int LÍMITE_ERRORES = 5;

    private ReentrantLock lock;
    private Paint guía;
    private Paint texto;
    private Paint estiloDígito;
    private DatosOCR datosOCR;
    private char dígito = '?';
    private int cuentaAtrás = 0;
    private int confirmación = 0;
    private int errores = 0;
    private boolean depuración = false;

    Pantalla(Context context, DatosOCR datosOCR, ReentrantLock lock) {
        super(context);
        this.datosOCR = datosOCR;
        this.lock = lock;
        guía = new Paint();
        guía.setARGB(200, 255, 255, 255);
        texto = new Paint();
        texto.setColor(Color.WHITE);
        texto.setAntiAlias(true);
        estiloDígito = new Paint();
        estiloDígito.setTextAlign(Paint.Align.CENTER);
        estiloDígito.setAntiAlias(true);
        estiloDígito.setColor(Color.WHITE);
    }

    protected void onDraw(Canvas canvas) {
        float ratioY = (float) canvas.getHeight() / datosOCR.getAltoImagen();
        float ratioX = (float) canvas.getWidth() / datosOCR.getAnchoImagen();
        Segmento[] filas = datosOCR.getFilas();
        if (filas != null) {
            Segmento[] columnas = datosOCR.getColumnas();
            if (columnas == null) {
                float y = filas[0].posición * ratioY;
                canvas.drawRect(0, y, canvas.getWidth(), y + ratioY, guía);
                for (Segmento fila : filas) {
                    y = (fila.posición + fila.tamaño) * ratioY;
                    canvas.drawRect(0, y, canvas.getWidth(), y + 1, guía);
                }
            } else
                for (int fila = 0; fila < filas.length; fila++) {
                    float y1 = filas[fila].posición * ratioY;
                    float y2 = (filas[fila].posición + filas[fila].tamaño)
                               * ratioY - 1;
                    for (int columna = 0;
                         columna < columnas.length;
                         columna++)
                        if (datosOCR.esCarácterSignificativo(columna, fila)) {
                            float x1 = columnas[columna].posición * ratioX;
                            float x2 = (columnas[columna].posición
                                        + columnas[columna].tamaño)
                                       * ratioX - 1;
                            canvas.drawRect(x1, y1, x2, y2, guía);
                        }
                }
        }
        texto.setTextSize(canvas.getHeight() / 22);
        String mensaje = "Enfocar parte posterior del DNI";
        float x = 5;
        if (depuración) {
            float y = texto.getTextSize() + 5;
            texto.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("ancho: " + datosOCR.getAnchoImagen(),
                            x, y, texto);
            canvas.drawText("alto: " + datosOCR.getAltoImagen(),
                            x, y * 2, texto);
            canvas.drawText("encontrado dígito: "
                            + datosOCR.getResultadosEncontradoDígito(),
                            x, y * 3, texto);
            canvas.drawText("encontrado columnas: "
                            + datosOCR.getResultadosEncontradoColumnas(),
                            x, y * 4, texto);
            canvas.drawText("encontrado filas: "
                            + datosOCR.getResultadosEncontradoFilas(),
                            x, y * 5, texto);
            canvas.drawText("no encontrado: "
                            + datosOCR.getResultadosNoEncontrado(),
                            x, y * 6, texto);
        }
        x = canvas.getWidth() / 2;
        float y = canvas.getHeight() - texto.getTextSize();
        texto.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(mensaje, x, y, texto);
        y = canvas.getHeight() / 2;
        canvas.drawRect(0, y, canvas.getWidth(), y + 1, guía);
        canvas.drawRect(x, y / 2, x + 1, y * 1.5f, guía);
        char nuevoDígito = datosOCR.getDígito();
        if (nuevoDígito == '?') {
            if (dígito != '?' && confirmación < CONFIRMACIÓN_ACEPTABLE
                && ++errores >= LÍMITE_ERRORES)
                dígito = '?';
        } else if (dígito == nuevoDígito)
            confirmación++;
        else {
            confirmación = 0;
            errores = 0;
            dígito = nuevoDígito;
            cuentaAtrás = INICIO_CUENTA_ATRÁS;
        }
        if (dígito != '?' && confirmación >= CONFIRMACIÓN_ACEPTABLE
            && cuentaAtrás > 0) {
            estiloDígito.setTextSize(canvas.getHeight() / 2);
            y = canvas.getHeight() / 2 + estiloDígito.getTextSize() / 3;
            canvas.drawText(Character.toString(dígito), x, y, estiloDígito);
            if (--cuentaAtrás == 0)
                dígito = '?';
        }
        if (lock.isLocked())
            lock.unlock();
    }

    public void cambiaDepuración() {
        depuración = !depuración;
    }
}

class Segmento {
    public int posición;
    public int tamaño;

    public void copiar(Segmento otro) {
        posición = otro.posición;
        tamaño = otro.tamaño;
    }
}

class Colisión extends Segmento {
    public boolean esColisión;
}

class DatosOCR {
    private static final int FACTOR_UMBRAL_ÓPTIMO_CARÁCTER = 4;

    private static final int FILA_NÚMEROS = 0;
    private static final int COLUMNA_NÚMEROS = 5;
    private static final int TAMAÑO_NÚMERO_SOPORTE = 9;
    private static final int TAMAÑO_NÚMERO_DNI = 8;
    private static final int TAMAÑO_NIF = TAMAÑO_NÚMERO_DNI + 1;

    private static final int COLUMNA_NIF_DNI = COLUMNA_NÚMEROS;
    private static final int COLUMNA_LETRA_DNI =
        COLUMNA_NÚMEROS + TAMAÑO_NÚMERO_DNI;
    private static final int COLUMNA_DÍGITO_CONTROL_NIF =
        COLUMNA_NÚMEROS + TAMAÑO_NIF;
    private static final int COLUMNA_FINAL_LETRAS_NÚMERO_SOPORTE_DNIE =
        COLUMNA_NÚMEROS + 3;
    private static final int COLUMNA_NIF_DNIE =
        COLUMNA_NÚMEROS + TAMAÑO_NÚMERO_SOPORTE + 1;
    private static final int COLUMNA_LETRA_DNIE =
        COLUMNA_NIF_DNIE + TAMAÑO_NÚMERO_DNI;
    private static final int COLUMNA_ÚLTIMO_DÍGITO_DNIE =
        COLUMNA_NIF_DNIE + TAMAÑO_NÚMERO_DNI - 1;
    private static final int COLUMNA_FINAL_NIF_DNIE =
        COLUMNA_NIF_DNIE + TAMAÑO_NIF;

    private static final int FILA_FECHAS = 1;
    private static final int COLUMNA_FECHA_NACIMIENTO = 0;
    private static final int TAMAÑO_FECHA = 6;
    private static final int COLUMNA_FECHA_CADUCIDAD =
        COLUMNA_FECHA_NACIMIENTO + TAMAÑO_FECHA + 1 + 1;
    private static final int COLUMNA_DÍGITO_CONTROL_FECHA_NACIMIENTO =
        COLUMNA_FECHA_NACIMIENTO + TAMAÑO_FECHA;
    private static final int COLUMNA_DÍGITO_CONTROL_FECHA_CADUCIDAD =
        COLUMNA_FECHA_CADUCIDAD + TAMAÑO_FECHA;
    private static final int TAMAÑO_DATOS_DNI =
        TAMAÑO_NIF + 1 + TAMAÑO_FECHA + 1 + TAMAÑO_FECHA + 1;
    private static final int TAMAÑO_DATOS_DNIE =
        TAMAÑO_NÚMERO_SOPORTE + 1 + TAMAÑO_NIF + TAMAÑO_FECHA + 1
        + TAMAÑO_FECHA + 1;

    private static final int NÚMERO_FILAS = FILA_FECHAS + 1;
    private static final int NÚMERO_COLUMNAS =
            COLUMNA_NIF_DNIE + TAMAÑO_NIF;

    private static final String DÍGITOS = "0123456789";
    private static final String DÍGITOS_O_NULO = "0123456789<";
    private static final String LETRAS_NIF = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final String LETRAS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int[] PESOS = { 7, 3, 1 };

    private static final int FORMATO_DNI = 1;
    private static final int FORMATO_DNIE = 2;

    private int anchoImagen;
    private int altoImagen;
    private byte[] píxeles;

    private int desviaciónMáximaTamañosFilas;
    private int desviaciónMáximaTamañosSepFilas;
    private int desviaciónMáximaTamañosColumnas;
    private int desviaciónMáximaTamañosSepColumnas;

    private Colisión[] colisiones;
    private int cantidadColisiones;
    private int índiceColisiones;

    private int[] valoresFilas;
    private Segmento[] filas;
    private Segmento[] candidatoFilas;
    private Segmento[] mejorCandidatoFilas;
    private int[] tamañosFilas;
    private int[] tamañosSepFilas;

    private int[] valoresColumnas;
    private Segmento[] columnas;
    private Segmento[] candidatoColumnas;
    private Segmento[] mejorCandidatoColumnas;
    private int[] tamañosColumnas;
    private int[] tamañosSepColumnas;

    private HashMap<Character, boolean[][]> plantillas;

    private int formato;
    private char[] númeroSoporte;
    private char dígitoControlCódigoSoporte;
    private char[] númeroDNI;
    private char letraNúmeroDNI;
    private char[] NIF;
    private char dígitoControlNIF;
    private char[] fechaNacimiento;
    private char dígitoControlFechaNacimiento;
    private char[] fechaCaducidad;
    private char dígitoControlFechaCaducidad;
    private char[] datosDNI;
    private char[] datosDNIE;
    private char dígito;

    private long resultadosEncontradoDígito = 0;
    private long resultadosEncontradoColumnas = 0;
    private long resultadosEncontradoFilas = 0;
    private long resultadosNoEncontrado = 0;

    DatosOCR(Context context) {
        candidatoColumnas = new Segmento[NÚMERO_COLUMNAS];
        mejorCandidatoColumnas = new Segmento[NÚMERO_COLUMNAS];
        for (int i = 0; i < NÚMERO_COLUMNAS; i++) {
            candidatoColumnas[i] = new Segmento();
            mejorCandidatoColumnas[i] = new Segmento();
        }

        tamañosFilas = new int[NÚMERO_FILAS + 1];
        tamañosSepFilas = new int[NÚMERO_FILAS];
        tamañosColumnas = new int[NÚMERO_COLUMNAS];
        tamañosSepColumnas = new int[NÚMERO_COLUMNAS - 1];

        candidatoFilas = new Segmento[NÚMERO_FILAS];
        mejorCandidatoFilas = new Segmento[NÚMERO_FILAS];
        for (int i = 0; i < NÚMERO_FILAS; i++) {
            candidatoFilas[i] = new Segmento();
            mejorCandidatoFilas[i] = new Segmento();
        }

        númeroSoporte = new char[TAMAÑO_NÚMERO_SOPORTE];
        númeroDNI = new char[TAMAÑO_NÚMERO_DNI];
        NIF = new char[TAMAÑO_NIF];
        fechaNacimiento = new char[TAMAÑO_FECHA];
        fechaCaducidad = new char[TAMAÑO_FECHA];
        datosDNI = new char[TAMAÑO_DATOS_DNI];
        datosDNIE = new char[TAMAÑO_DATOS_DNIE];

        plantillas = new HashMap<Character, boolean[][]>();
        cargarPlantilla(context, '0', R.drawable.char_0);
        cargarPlantilla(context, '1', R.drawable.char_1);
        cargarPlantilla(context, '2', R.drawable.char_2);
        cargarPlantilla(context, '3', R.drawable.char_3);
        cargarPlantilla(context, '4', R.drawable.char_4);
        cargarPlantilla(context, '5', R.drawable.char_5);
        cargarPlantilla(context, '6', R.drawable.char_6);
        cargarPlantilla(context, '7', R.drawable.char_7);
        cargarPlantilla(context, '8', R.drawable.char_8);
        cargarPlantilla(context, '9', R.drawable.char_9);
        cargarPlantilla(context, 'A', R.drawable.char_a);
        cargarPlantilla(context, 'B', R.drawable.char_b);
        cargarPlantilla(context, 'C', R.drawable.char_c);
        cargarPlantilla(context, 'D', R.drawable.char_d);
        cargarPlantilla(context, 'E', R.drawable.char_e);
        cargarPlantilla(context, 'F', R.drawable.char_f);
        cargarPlantilla(context, 'G', R.drawable.char_g);
        cargarPlantilla(context, 'H', R.drawable.char_h);
        cargarPlantilla(context, 'I', R.drawable.char_i);
        cargarPlantilla(context, 'J', R.drawable.char_j);
        cargarPlantilla(context, 'K', R.drawable.char_k);
        cargarPlantilla(context, 'L', R.drawable.char_l);
        cargarPlantilla(context, 'M', R.drawable.char_m);
        cargarPlantilla(context, 'N', R.drawable.char_n);
        cargarPlantilla(context, 'O', R.drawable.char_o);
        cargarPlantilla(context, 'P', R.drawable.char_p);
        cargarPlantilla(context, 'Q', R.drawable.char_q);
        cargarPlantilla(context, 'R', R.drawable.char_r);
        cargarPlantilla(context, 'S', R.drawable.char_s);
        cargarPlantilla(context, 'T', R.drawable.char_t);
        cargarPlantilla(context, 'U', R.drawable.char_u);
        cargarPlantilla(context, 'V', R.drawable.char_v);
        cargarPlantilla(context, 'W', R.drawable.char_w);
        cargarPlantilla(context, 'X', R.drawable.char_x);
        cargarPlantilla(context, 'Y', R.drawable.char_y);
        cargarPlantilla(context, 'Z', R.drawable.char_z);
        cargarPlantilla(context, '<', R.drawable.char_lt);
    }

    private void cargarPlantilla(Context context, char carácter,
                                 int resource) {
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(),
                                                     resource);
        boolean[][] plantilla = new boolean[bitmap.getWidth()][];
        for (int x = 0; x < plantilla.length; x++) {
            plantilla[x] = new boolean[bitmap.getHeight()];
            for (int y = 0; y < plantilla[x].length; y++)
                plantilla[x][y] = bitmap.getPixel(x, y) == 0xff000000;
            plantillas.put(carácter, plantilla);
        }
    }

    public void setTamañoImagen(int ancho, int alto) {
        valoresColumnas = new int[ancho];
        valoresFilas = new int[alto];
        colisiones = new Colisión[Math.max(ancho, alto)];
        for (int i = 0; i < colisiones.length; i++)
            colisiones[i] = new Colisión();
        anchoImagen = ancho;
        altoImagen = alto;
        float ratioY = alto / 240;
        desviaciónMáximaTamañosFilas = (int) Math.ceil(2 * ratioY);
        desviaciónMáximaTamañosSepFilas = (int) Math.ceil(ratioY);
        float ratioX = ancho / 320;
        desviaciónMáximaTamañosColumnas = (int) Math.ceil(4 * ratioX);
        desviaciónMáximaTamañosSepColumnas = (int) Math.ceil(4 * ratioX);
    }

    private int max(int[] valores) {
        int m = Integer.MIN_VALUE;
        for (int valor : valores)
            m = Math.max(m, valor);
        return m;
    }

    private boolean desviaciónMáxima(int[] valores, int máximo) {
        int promedio = 0;
        for (int valor : valores)
            promedio += valor;
        promedio /= valores.length;
        int desviaciónEstándar = 0;
        for (int valor : valores)
            desviaciónEstándar += Math.pow(valor - promedio, 2);
        desviaciónEstándar = (int) Math.sqrt(desviaciónEstándar);
        return desviaciónEstándar >= 0 && desviaciónEstándar <= máximo;
    }

    private int luminosidad(int x, int y) {
        return píxeles[x + anchoImagen * y] & 0xff;
    }

    private void calcularValoresFilas() {
        for (int y = 0; y < altoImagen; y++) {
            int total = 0;
            for (int x = 0; x < anchoImagen; x++)
                total += luminosidad(x, y);
            valoresFilas[y] = total / anchoImagen;
        }
    }

    private void calcularValoresColumnas() {
        int inicio = filas[0].posición;
        int fin = filas[1].posición + filas[1].tamaño;
        for (int x = 0; x < anchoImagen; x++) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int y = inicio; y < fin; y++) {
                int v = luminosidad(x, y);
                min = Math.min(v, min);
                max = Math.max(v, max);
            }
            valoresColumnas[x] = min - max;
        }
    }

    private void calcularColisiones(int[] valores, int umbral) {
        cantidadColisiones = 0;
        for (int i = 0; i < valores.length; i++) {
            boolean colisión = valores[i] >= umbral;
            if (cantidadColisiones == 0
                || colisiones[cantidadColisiones - 1].esColisión
                   != colisión) {
                colisiones[cantidadColisiones].esColisión = colisión;
                colisiones[cantidadColisiones].posición = i;
                colisiones[cantidadColisiones].tamaño = 1;
                cantidadColisiones++;
            } else
                colisiones[cantidadColisiones - 1].tamaño++;
        }
    }

    private void ajustarSegmentos(Segmento[] segmentos) {
        int m, n;
        for (int i = 0; i < segmentos.length; i++) {
            if (i == 0) {
                n = (segmentos[i + 1].posición
                     - (segmentos[i].posición + segmentos[i].tamaño))
                    / 2;
                m = n;
            } else if (i == segmentos.length - 1) {
                m = (segmentos[i].posición
                     - (segmentos[i - 1].posición + segmentos[i - 1].tamaño));
                n = m;
            } else {
                m = (segmentos[i].posición
                     - (segmentos[i - 1].posición + segmentos[i - 1].tamaño));
                n = (segmentos[i + 1].posición
                     - (segmentos[i].posición + segmentos[i].tamaño))
                    / 2;
            }
            segmentos[i].posición -= m;
            segmentos[i].tamaño += m + n;
        }
    }

    private boolean candidatoFilas() {
        índiceColisiones++;
        if (cantidadColisiones >= 7 + índiceColisiones) {
            Colisión a, b, c, d, e, f, g;
            a = colisiones[índiceColisiones];
            b = colisiones[índiceColisiones + 1];
            c = colisiones[índiceColisiones + 2];
            d = colisiones[índiceColisiones + 3];
            e = colisiones[índiceColisiones + 4];
            f = colisiones[índiceColisiones + 5];
            g = colisiones[índiceColisiones + 6];
            tamañosFilas[0] = b.tamaño;
            tamañosFilas[1] = d.tamaño;
            tamañosFilas[2] = f.tamaño;
            tamañosSepFilas[0] = c.tamaño;
            tamañosSepFilas[1] = e.tamaño;
            int tamañoMaxFilas = max(tamañosFilas);
            int mitad = (f.posición + f.tamaño - b.posición) / 2 + b.posición;
            if (d.posición < mitad
                && d.posición + d.tamaño > mitad
                && tamañoMaxFilas > max(tamañosSepFilas)
                && tamañoMaxFilas < a.tamaño
                && tamañoMaxFilas < g.tamaño
                && desviaciónMáxima(tamañosFilas,
                                    desviaciónMáximaTamañosFilas)
                && desviaciónMáxima(tamañosSepFilas,
                                    desviaciónMáximaTamañosSepFilas)) {
                for (int fila = 0;
                     fila < candidatoFilas.length;
                     fila++)
                    candidatoFilas[fila].copiar(
                        colisiones[índiceColisiones + 1 + fila * 2]);
                return true;
            }
        }
        return false;
    }

    private Segmento[] encuentraFilas() {
        calcularValoresFilas();
        boolean encontrado = false;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int valor : valoresFilas) {
            min = Math.min(min, valor);
            max = Math.max(max, valor);
        }
        int totalCandidato, totalMejor = 0;
        for (int umbral = min; umbral < max; umbral++) {
            calcularColisiones(valoresFilas, umbral);
            restablecerÍndiceColisiones();
            while (candidatoFilas()) {
                totalCandidato = totalTamaños(candidatoFilas);
                if (!encontrado || totalCandidato > totalMejor) {
                    encontrado = true;
                    for (int i = 0; i < candidatoFilas.length; i++)
                        mejorCandidatoFilas[i].copiar(candidatoFilas[i]);
                    totalMejor = totalCandidato;
                }
            }
        }
        if (encontrado) {
            ajustarSegmentos(mejorCandidatoFilas);
            return mejorCandidatoFilas;
        }
        return null;
    }

    private boolean candidatoColumnas() {
        índiceColisiones++;
        int fin = candidatoColumnas.length * 2 + 1;
        if (cantidadColisiones >= fin + índiceColisiones) {
            for (int columna = 0;
                 columna < candidatoColumnas.length;
                 columna++) {
                tamañosColumnas[columna] =
                    colisiones[índiceColisiones + 1 + columna * 2].tamaño;
                if (columna < candidatoColumnas.length - 1)
                    tamañosSepColumnas[columna] =
                        colisiones[índiceColisiones + 2 + columna * 2].tamaño;
            }
            if (desviaciónMáxima(tamañosColumnas,
                                 desviaciónMáximaTamañosColumnas)
                && desviaciónMáxima(tamañosSepColumnas,
                                    desviaciónMáximaTamañosSepColumnas)) {
                for (int columna = 0;
                     columna < candidatoColumnas.length;
                     columna++)
                    candidatoColumnas[columna].copiar(
                        colisiones[índiceColisiones + 1 + columna * 2]);
                return true;
            }
        }
        return false;
    }

    private int totalTamaños(Segmento[] segmentos) {
        int total = 0;
        for (Segmento segmento : segmentos)
            total += segmento.tamaño;
        return total;
    }

    private Segmento[] encuentraColumnas() {
        calcularValoresColumnas();
        boolean encontrado = false;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int valor : valoresColumnas) {
            min = Math.min(min, valor);
            max = Math.max(max, valor);
        }
        int totalCandidato, totalMejor = 0;
        for (int umbral = min; umbral < max; umbral++) {
            calcularColisiones(valoresColumnas, umbral);
            restablecerÍndiceColisiones();
            while (candidatoColumnas()) {
                totalCandidato = totalTamaños(candidatoColumnas);
                if (!encontrado || (candidatoColumnas[0].posición
                                    < mejorCandidatoColumnas[0].posición
                                    && totalCandidato > totalMejor)) {
                    encontrado = true;
                    for (int i = 0; i < candidatoColumnas.length; i++)
                        mejorCandidatoColumnas[i].copiar(
                            candidatoColumnas[i]);
                    totalMejor = totalCandidato;
                }
            }
        }
        if (encontrado) {
            ajustarSegmentos(mejorCandidatoColumnas);
            return mejorCandidatoColumnas;
        }
        return null;
    }

    private void restablecerÍndiceColisiones() {
        índiceColisiones = 0;
        if (colisiones[índiceColisiones].esColisión)
            índiceColisiones--;
    }

    private int luminosidadCarácter(int columna, int fila, int x, int y) {
        return luminosidad(x + columnas[columna].posición,
                           y + filas[fila].posición);
    }

    private boolean bitCarácter(int columna, int fila, int x, int y,
                                int umbral) {
        return luminosidadCarácter(columna, fila, x, y) < umbral;
    }

    private int xInicioCarácter(int columna, int fila, int umbral) {
        for (int x = 0; x < columnas[columna].tamaño; x++)
            for (int y = 0; y < filas[fila].tamaño; y++)
                if (bitCarácter(columna, fila, x, y, umbral))
                    return x;
        return 0;
    }

    private int xFinCarácter(int columna, int fila, int umbral) {
        for (int x = columnas[columna].tamaño - 1; x > 0; x--)
            for (int y = 0; y < filas[fila].tamaño; y++)
                if (bitCarácter(columna, fila, x, y, umbral))
                    return x;
        return columnas[columna].tamaño - 1;
    }

    private int yInicioCarácter(int columna, int fila, int umbral) {
        for (int y = 0; y < filas[fila].tamaño; y++)
            for (int x = 0; x < columnas[columna].tamaño; x++)
                if (bitCarácter(columna, fila, x, y, umbral))
                    return y;
        return 0;
    }

    private int yFinCarácter(int columna, int fila, int umbral) {
        for (int y = filas[fila].tamaño - 1; y > 0; y--)
            for (int x = 0; x < columnas[columna].tamaño; x++)
                if (bitCarácter(columna, fila, x, y, umbral))
                    return y;
        return filas[fila].tamaño - 1;
    }

    private int calcularUmbralÓptimoCarácter(int columna, int fila) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int y = 0; y < filas[fila].tamaño; y++)
            for (int x = 0; x < columnas[columna].tamaño; x++) {
                int v = luminosidadCarácter(columna, fila, x, y);
                min = Math.min(v, min);
                max = Math.max(v, max);
            }
        int umbral = max;
        int óptimo = filas[fila].tamaño * columnas[columna].tamaño
                     / FACTOR_UMBRAL_ÓPTIMO_CARÁCTER;
        int encendidos;
        do {
            encendidos = 0;
            for (int y = 0; y < filas[fila].tamaño; y++)
                for (int x = 0; x < columnas[columna].tamaño; x++)
                    if (bitCarácter(columna, fila, x, y, umbral))
                        encendidos++;
        } while (--umbral > min && encendidos > óptimo);
        return umbral;
    }

    private int puntuaciónPlantilla(int columna, int fila,
                                    int xInicio, int yInicio,
                                    int ancho, int alto, int umbral,
                                    boolean[][] plantilla) {
        int puntuación = 0;
        for (int x = 0; x < ancho; x++)
            for (int y = 0; y < alto; y++) {
                int tx = x * plantilla.length / ancho;
                int ty = y * plantilla[0].length / alto;
                if (plantilla[tx][ty] == bitCarácter(columna, fila,
                                                     x + xInicio,
                                                     y + yInicio,
                                                     umbral))
                    puntuación++;
                else
                    puntuación--;
            }
        return puntuación;
    }

    private String caracteresPosibles(int columna, int fila) {
        if (fila == 0) {
            if (formato == FORMATO_DNIE) {
                if (columna >= COLUMNA_NÚMEROS
                    && columna < COLUMNA_FINAL_LETRAS_NÚMERO_SOPORTE_DNIE)
                    return LETRAS;
                if (columna == COLUMNA_LETRA_DNIE)
                    return LETRAS_NIF;
            } else if (columna == COLUMNA_LETRA_DNI)
                return LETRAS_NIF;
        }
        return DÍGITOS;
    }

    private char valorCarácter(int columna, int fila) {
        return valorCarácter(
            columna, fila, caracteresPosibles(columna, fila));
    }

    private char valorCarácter(int columna, int fila, String caracteres) {
        int umbral = calcularUmbralÓptimoCarácter(columna, fila);
        int xInicio = xInicioCarácter(columna, fila, umbral);
        int yInicio = yInicioCarácter(columna, fila, umbral);
        int ancho = xFinCarácter(columna, fila, umbral) - xInicio;
        int alto = yFinCarácter(columna, fila, umbral) - yInicio;
        Character valor = '?';
        int mejorPuntuación = 0;
        for (int i = 0; i < caracteres.length(); i++) {
            int puntuación = puntuaciónPlantilla(
                columna, fila, xInicio, yInicio, ancho, alto, umbral,
                plantillas.get(caracteres.charAt(i)));
            if (puntuación > mejorPuntuación) {
                valor = caracteres.charAt(i);
                mejorPuntuación = puntuación;
            }
        }
        return valor;
    }

    private char dígitoControl(char[] cs) {
        int n = 0;
        for (int i = 0; i < cs.length; i++)
            if (Character.isDigit(cs[i]))
                n += (cs[i] - '0') * PESOS[i % PESOS.length];
            else if (Character.isLetter(cs[i]))
                n += (cs[i] - 'A') * PESOS[i % PESOS.length];
        return (char) ('0' + n % 10);
    }

    public static char calcularLetraNúmeroDNI(char[] cs) {
        int n = 0;
        for (char c : cs)
            n = c - '0' + n * 10;
        return LETRAS_NIF.charAt(n % LETRAS_NIF.length());
    }

    private boolean códigoSoporteVálido() {
        for (int i = 0; i < númeroSoporte.length; i++)
            númeroSoporte[i] = valorCarácter(i + COLUMNA_NÚMEROS,
                                             FILA_NÚMEROS);
        dígitoControlCódigoSoporte =
            valorCarácter(COLUMNA_NÚMEROS + númeroSoporte.length,
                          FILA_NÚMEROS);
        return dígitoControl(númeroSoporte) == dígitoControlCódigoSoporte;
    }

    private boolean númeroDNIVálido() {
        int inicio = formato == FORMATO_DNI
                         ? COLUMNA_NIF_DNI
                         : COLUMNA_NIF_DNIE;
        for (int i = 0; i < númeroDNI.length; i++)
            númeroDNI[i] = valorCarácter(i + inicio, FILA_NÚMEROS);
        letraNúmeroDNI = valorCarácter(inicio + númeroDNI.length,
                                       FILA_NÚMEROS);
        return calcularLetraNúmeroDNI(númeroDNI) == letraNúmeroDNI;
    }

    private boolean NIFVálido() {
        for (int i = 0; i < númeroDNI.length; i++)
            NIF[i] = númeroDNI[i];
        NIF[NIF.length - 1] = letraNúmeroDNI;
        dígitoControlNIF = valorCarácter(COLUMNA_DÍGITO_CONTROL_NIF,
                                         FILA_NÚMEROS);
        return dígitoControl(NIF) == dígitoControlNIF;
    }

    private boolean fechaNacimientoVálida() {
        for (int i = 0; i < fechaNacimiento.length; i++)
            fechaNacimiento[i] =
                valorCarácter(i + COLUMNA_FECHA_NACIMIENTO, FILA_FECHAS);
        dígitoControlFechaNacimiento =
            valorCarácter(COLUMNA_DÍGITO_CONTROL_FECHA_NACIMIENTO,
                          FILA_FECHAS);
        return dígitoControl(fechaNacimiento) == dígitoControlFechaNacimiento;
    }

    private boolean fechaCaducidadVálida() {
        for (int i = 0; i < fechaCaducidad.length; i++)
            fechaCaducidad[i] =
                valorCarácter(i + COLUMNA_FECHA_CADUCIDAD, FILA_FECHAS);
        dígitoControlFechaCaducidad =
            valorCarácter(COLUMNA_DÍGITO_CONTROL_FECHA_CADUCIDAD,
                          FILA_FECHAS);
        return dígitoControl(fechaCaducidad) == dígitoControlFechaCaducidad;
    }

    private boolean datosLeídosCorrectamente() {
        return númeroDNIVálido() && fechaNacimientoVálida()
               && fechaCaducidadVálida()
               && ((formato != FORMATO_DNI && códigoSoporteVálido())
                   || (formato == FORMATO_DNI && NIFVálido()));
    }

    private char calculaDígito() {
        int i = 0;
        if (formato != FORMATO_DNI) {
            for (char c : númeroSoporte)
                datosDNIE[i++] = c;
            datosDNIE[i++] = dígitoControlCódigoSoporte;
            for (char c : númeroDNI)
                datosDNIE[i++] = c;
            datosDNIE[i++] = letraNúmeroDNI;
            for (char c : fechaNacimiento)
                datosDNIE[i++] = c;
            datosDNIE[i++] = dígitoControlFechaNacimiento;
            for (char c : fechaCaducidad)
                datosDNIE[i++] = c;
            datosDNIE[i++] = dígitoControlFechaCaducidad;
            return dígitoControl(datosDNIE);
        }
        for (char c : NIF)
            datosDNI[i++] = c;
        datosDNI[i++] = dígitoControlNIF;
        for (char c : fechaNacimiento)
            datosDNI[i++] = c;
        datosDNI[i++] = dígitoControlFechaNacimiento;
        for (char c : fechaCaducidad)
            datosDNI[i++] = c;
        datosDNI[i++] = dígitoControlFechaCaducidad;
        return dígitoControl(datosDNI);
    }

    public boolean esCarácterSignificativo(int columna, int fila) {
        return (fila == FILA_NÚMEROS && columna >= COLUMNA_NÚMEROS
                && ((formato == FORMATO_DNIE
                        && columna < COLUMNA_FINAL_NIF_DNIE)
                    || (formato == FORMATO_DNI
                        && columna <= COLUMNA_DÍGITO_CONTROL_NIF)))
               || (fila == FILA_FECHAS
                   && ((columna >= COLUMNA_FECHA_NACIMIENTO
                        && columna <= COLUMNA_DÍGITO_CONTROL_FECHA_NACIMIENTO)
                       || (columna >= COLUMNA_FECHA_CADUCIDAD
                           && columna
                              <= COLUMNA_DÍGITO_CONTROL_FECHA_CADUCIDAD)));
    }

    public boolean encontrar(byte[] píxeles) {
        this.píxeles = píxeles;
        columnas = null;
        dígito = '?';
        if ((filas = encuentraFilas()) != null) {
            if ((columnas = encuentraColumnas()) != null) {
                if ('<' == valorCarácter(COLUMNA_ÚLTIMO_DÍGITO_DNIE,
                                         FILA_NÚMEROS, DÍGITOS_O_NULO))
                    formato = FORMATO_DNI;
                else 
                    formato = FORMATO_DNIE;
                if (datosLeídosCorrectamente()) {
                    dígito = calculaDígito();
                    resultadosEncontradoDígito++;
                    return true;
                }
                resultadosEncontradoColumnas++;
            }
            resultadosEncontradoFilas++;
        }
        resultadosNoEncontrado++;
        return false;
    }

    public int getAnchoImagen() {
        return anchoImagen;
    }

    public int getAltoImagen() {
        return altoImagen;
    }

    public Segmento[] getColumnas() {
        return columnas;
    }

    public Segmento[] getFilas() {
        return filas;
    }

    public char getDígito() {
        return dígito;
    }

    public long getResultadosEncontradoDígito() {
        return resultadosEncontradoDígito;
    }

    public long getResultadosEncontradoColumnas() {
        return resultadosEncontradoColumnas;
    }

    public long getResultadosEncontradoFilas() {
        return resultadosEncontradoFilas;
    }

    public long getResultadosNoEncontrado() {
        return resultadosNoEncontrado;
    }
}