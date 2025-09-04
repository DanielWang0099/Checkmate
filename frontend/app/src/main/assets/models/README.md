# MobileNetV2 Screenshot Classifier Model

This directory should contain:
- `mobilenet_screenshot_classifier.tflite` - The TensorFlow Lite model file

## Model Requirements

The model should be a custom-trained MobileNetV2 model that can classify screenshots into categories:
- Text content (social media, news articles, messaging)
- Image content (photos, graphics, videos)
- UI elements (buttons, menus, system UI)

## How to obtain the model:

1. **Option 1: Train your own**
   - Use TensorFlow/Keras to train a MobileNetV2 on screenshot data
   - Convert to TensorFlow Lite format
   - Place the .tflite file in this directory

2. **Option 2: Use a pre-trained model**
   - Download a pre-trained MobileNetV2 from TensorFlow Hub
   - Fine-tune on screenshot classification task
   - Convert and place here

3. **Option 3: Placeholder for development**
   - For development/testing, you can create a dummy model
   - The ImageClassifier will gracefully handle missing models

## File expected:
```
mobilenet_screenshot_classifier.tflite
```

Size should be approximately 3-14 MB depending on quantization.

For now, the ImageClassifier will log an error but continue working without the model file.
