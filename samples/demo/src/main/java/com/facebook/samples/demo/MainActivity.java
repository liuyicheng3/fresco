/*
 * This file provided by Facebook is for non-commercial testing and evaluation
 * purposes only.  Facebook reserves all rights not expressly granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * FACEBOOK BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook.samples.demo;

import java.util.HashSet;
import java.util.Set;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;

import com.facebook.common.logging.FLog;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.facebook.imagepipeline.listener.RequestListener;
import com.facebook.imagepipeline.listener.RequestLoggingListener;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

public class MainActivity extends Activity {

    private ImageView mBaselineJpegView;
    private SimpleDraweeView mProgressiveJpegView;
    private SimpleDraweeView mAnimatedGifView;
    private SimpleDraweeView data_mult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FLog.setMinimumLoggingLevel(FLog.VERBOSE);
        Set<RequestListener> listeners = new HashSet<>();
        listeners.add(new RequestLoggingListener());
        ImagePipelineConfig config = ImagePipelineConfig.newBuilder(this)
                .setRequestListeners(listeners)
                .build();
        Fresco.initialize(this, config);
        setContentView(R.layout.activity_main);

        mBaselineJpegView = (SimpleDraweeView) findViewById(R.id.baseline_jpeg);
        mProgressiveJpegView = (SimpleDraweeView) findViewById(R.id.progressive_jpeg);

        mAnimatedGifView = (SimpleDraweeView) findViewById(R.id.animated_gif);
        data_mult = (SimpleDraweeView) findViewById(R.id.data_mult);



        mBaselineJpegView.setImageURI(Uri.parse("http://p18.qhimg.com/t0144d6a0802f22be4f.jpg"));

        Uri uri = Uri.parse("http://pooyak.com/p/progjpeg/jpegload.cgi?o=1");
        ImageRequest request = ImageRequestBuilder.newBuilderWithSource(uri)
                .setProgressiveRenderingEnabled(true)
                .build();
        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .build();
        mProgressiveJpegView.setController(controller);

        DraweeController animatedGifController = Fresco.newDraweeControllerBuilder()
                .setAutoPlayAnimations(true)
                .setUri(Uri.parse("http://static.abc001.sdo.com/web_3_0/chwm/2012/08/14/0809288ms4hsj71fzzh119.gif"))
                .build();
        mAnimatedGifView.setController(animatedGifController);

        DraweeController mult_controller = Fresco.newDraweeControllerBuilder()
                .setLowResImageRequest(ImageRequest.fromUri(Uri.parse("http://img.zwbk.org/baike/spic/2010/11/12/20101112104515812_2816.jpg")))
                .setImageRequest(ImageRequest.fromUri(Uri.parse("http://h.hiphotos.baidu.com/zhidao/pic/item/e61190ef76c6a7efd1e9f66bfffaaf51f2de66ab.jpg")))
                .build();
        data_mult.setController(mult_controller);


    }
}
