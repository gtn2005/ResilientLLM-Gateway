package dev.resilient.gateway.cache;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import dev.resilient.gateway.config.GatewayProperties;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "gateway.mode", havingValue = "gateway", matchIfMissing = true)
public class DjlMiniLmEmbeddingService implements EmbeddingService {
  private static final Logger log = LoggerFactory.getLogger(DjlMiniLmEmbeddingService.class);
  private final Path modelPath;
  private volatile ZooModel<String, float[]> model;
  private volatile HuggingFaceTokenizer tokenizer;
  private volatile boolean unavailable;

  public DjlMiniLmEmbeddingService(GatewayProperties settings) {
    modelPath = Path.of(settings.cache().embeddingModelPath());
  }

  private synchronized void initialize() {
    if (model != null || unavailable) {
      return;
    }
    if (!Files.exists(modelPath.resolve("model.onnx"))
        || !Files.exists(modelPath.resolve("tokenizer.json"))) {
      unavailable = true;
      log.warn(
          "MiniLM model files missing at {}. Semantic cache disabled until restart.", modelPath);
      return;
    }
    try {
      tokenizer = HuggingFaceTokenizer.newInstance(modelPath.resolve("tokenizer.json"));
      Criteria<String, float[]> criteria =
          Criteria.builder()
              .setTypes(String.class, float[].class)
              .optModelPath(modelPath)
              .optModelName("model")
              .optEngine("OnnxRuntime")
              .optTranslator(new MiniLmTranslator(tokenizer))
              .build();
      model = criteria.loadModel();
    } catch (Exception failure) {
      unavailable = true;
      log.error("MiniLM could not start. Semantic cache disabled.", failure);
    }
  }

  @Override
  public boolean ready() {
    if (model == null && !unavailable) {
      initialize();
    }
    return model != null;
  }

  @Override
  public float[] embed(String text) {
    if (!ready()) {
      throw new IllegalStateException("MiniLM model unavailable");
    }
    try (Predictor<String, float[]> predictor = model.newPredictor()) {
      return predictor.predict(text);
    } catch (Exception failure) {
      throw new IllegalStateException("MiniLM inference failed", failure);
    }
  }

  @PreDestroy
  public void close() {
    if (model != null) {
      model.close();
    }
    if (tokenizer != null) {
      tokenizer.close();
    }
  }

  private static final class MiniLmTranslator implements Translator<String, float[]> {
    private final HuggingFaceTokenizer tokenizer;
    private final ThreadLocal<long[]> mask = new ThreadLocal<>();

    private MiniLmTranslator(HuggingFaceTokenizer tokenizer) {
      this.tokenizer = tokenizer;
    }

    @Override
    public Batchifier getBatchifier() {
      return null;
    }

    @Override
    public NDList processInput(TranslatorContext context, String text) {
      Encoding encoding = tokenizer.encode(text);
      int size = Math.min(256, encoding.getIds().length);
      long[] ids = Arrays.copyOf(encoding.getIds(), size);
      long[] attention = Arrays.copyOf(encoding.getAttentionMask(), size);
      long[] typeIds = Arrays.copyOf(encoding.getTypeIds(), size);
      mask.set(attention);
      NDArray inputIds = context.getNDManager().create(ids, new Shape(1, size));
      inputIds.setName("input_ids");
      NDArray attentionMask = context.getNDManager().create(attention, new Shape(1, size));
      attentionMask.setName("attention_mask");
      NDArray tokenTypeIds = context.getNDManager().create(typeIds, new Shape(1, size));
      tokenTypeIds.setName("token_type_ids");
      return new NDList(inputIds, attentionMask, tokenTypeIds);
    }

    @Override
    public float[] processOutput(TranslatorContext context, NDList output) {
      float[] hidden = output.get(0).toFloatArray();
      long[] attention = mask.get();
      mask.remove();
      int dimensions = hidden.length / attention.length;
      float[] mean = new float[dimensions];
      int count = 0;
      for (int token = 0; token < attention.length; token++) {
        if (attention[token] == 0) {
          continue;
        }
        for (int dim = 0; dim < dimensions; dim++) {
          mean[dim] += hidden[token * dimensions + dim];
        }
        count++;
      }
      double norm = 0;
      for (int dim = 0; dim < dimensions; dim++) {
        mean[dim] /= Math.max(1, count);
        norm += mean[dim] * mean[dim];
      }
      norm = Math.sqrt(norm);
      for (int dim = 0; dim < dimensions; dim++) {
        mean[dim] /= (float) Math.max(norm, 1e-9);
      }
      return mean;
    }
  }
}
