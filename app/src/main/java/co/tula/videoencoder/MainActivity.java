package co.tula.videoencoder;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String INPUT           = "/storage/emulated/0/timed.mp4";
    private static final String OUTPUT          = "/storage/emulated/0/encoded.mp4";
    private static final String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n"
                                                  + "precision mediump float;\n"
                                                  + "varying vec2 vTextureCoord;\n"
                                                  + "uniform samplerExternalOES sTexture;\n" + "void main() {\n"
                                                  + "  vec4 color = texture2D(sTexture, vTextureCoord);\n"
                                                  + "  float colorR = (1.0 - color.r) / 1.0;\n"
                                                  + "  float colorG = (1.0 - color.g) / 1.0;\n"
                                                  + "  float colorB = (1.0 - color.b) / 1.0;\n"
                                                  + "  gl_FragColor = vec4(colorR, colorG, colorB, color.a);\n"
                                                  + "}\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.button).setOnClickListener(view -> startWrapper());
    }

    private void startWrapper() {
        TranscoderService.enqueueTranscode(this, INPUT, OUTPUT, FRAGMENT_SHADER);
    }
}
