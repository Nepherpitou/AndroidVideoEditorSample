package co.tula.videoencoder.encoder;

import android.graphics.PointF;
import android.graphics.RectF;

/**
 * Created by nephe on 04.10.2016.
 */
public class Sprite {

    public float scaleY = 1f;
    public float scaleX = 1f;// + 1f / 3f;
    public RectF  base;
    public PointF translation;
    private float angle = 0;

    public Sprite() {
        base = new RectF(-1f, 1f, 1f, -1f);
        translation = new PointF(0f, 0f);
        angle = 0f;
    }

    public Sprite updateAngleDeg(float angle) {
        this.angle = (float) ((Math.PI / 180.f) * angle);
        return this;
    }

    public float[] getTransformedVertices() {
        // Start with scaling
        float x1 = base.left * scaleX;
        float x2 = base.right * scaleX;
        float y1 = base.bottom * scaleY;
        float y2 = base.top * scaleY;

        // We now detach from our Rect because when rotating,
        // we need the seperate points, so we do so in opengl order
        PointF one   = new PointF(x1, y1);
        PointF two   = new PointF(x1, y2);
        PointF three = new PointF(x2, y1);
        PointF four  = new PointF(x2, y2);

        // We create the sin and cos function once,
        // so we do not have calculate them each time.
        float s = (float) Math.sin(angle);
        float c = (float) Math.cos(angle);

        // Then we rotate each point
        one.x = x1 * c - y2 * s;
        one.y = x1 * s + y2 * c;
        two.x = x1 * c - y1 * s;
        two.y = x1 * s + y1 * c;
        three.x = x2 * c - y1 * s;
        three.y = x2 * s + y1 * c;
        four.x = x2 * c - y2 * s;
        four.y = x2 * s + y2 * c;

        // Finally we translate the sprite to its correct position.
        one.x += translation.x;
        one.y += translation.y;
        two.x += translation.x;
        two.y += translation.y;
        three.x += translation.x;
        three.y += translation.y;
        four.x += translation.x;
        four.y += translation.y;

        // We now return our float array of vertices.
        return new float[]{two.x, two.y, 0.0f,
                           three.x, three.y, 0.0f,
                           one.x, one.y, 0.0f,
                           four.x, four.y, 0.0f,};
    }
}
