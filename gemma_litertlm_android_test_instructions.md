# Gemma 3 1B IT LiteRT-LM Android Test

## Purpose

This document records the **minimal Android test that successfully ran
Gemma locally on a physical Xiaomi 11 Lite 5G NE**.

The goal of this test was only:

``` text
TEXT INPUT → GEMMA RUNS ON DEVICE → TEXT RESPONSE
```

It was **not** the full smishing detector. No backend, database,
authentication, networking, or SMS integration was required.

------------------------------------------------------------------------

## 1. Tested Device

  Item        Value
  ----------- -----------------------------------
  Phone       Xiaomi 11 Lite 5G NE
  Android     Android 14
  OS          HyperOS 2.0.8.0
  SoC         Qualcomm Snapdragon 778G
  Storage     128 GB
  RAM         6 GB or 8 GB
  Test type   Physical-device Android inference

The test successfully produced a Gemma response on this physical device.

------------------------------------------------------------------------

## 2. Gemma Model Used

The model that successfully ran was:

``` text
gemma3-1b-it-int4.litertlm
```

### Model details

-   **Model family:** Gemma 3
-   **Size:** 1B parameters
-   **Variant:** Instruction-tuned (`IT`)
-   **Quantization:** INT4
-   **Model format:** `.litertlm`
-   **Runtime:** LiteRT-LM
-   **Model file size:** 584,417,280 bytes (about 584 MB)

The LiteRT Community Gemma 3 1B IT repository provides this `.litertlm`
artifact and identifies LiteRT-LM as its runtime. The repository also
lists the corresponding `.task` artifact separately.

Model repository:

https://huggingface.co/litert-community/Gemma3-1B-IT

LiteRT-LM documentation:

https://ai.google.dev/edge/litert-lm

> Note: The `.litertlm` model tested here is different from the
> `gemma3-1b-it-int4.task` model used in the original application. The
> successful test therefore confirms the `.litertlm` + LiteRT-LM setup,
> not the existing `.task` setup.

------------------------------------------------------------------------

## 3. What to Download

Download this exact model file:

``` text
gemma3-1b-it-int4.litertlm
```

It is found in:

``` text
litert-community/Gemma3-1B-IT
```

The repository is gated by the Gemma license, so a Hugging Face account
may be required to access the model file. Review and accept the
applicable Gemma terms before downloading.

Do **not** substitute these files for this test:

``` text
gemma3-1b-it-int4.task
gemma3-1b-it-int4-web.task
```

The successful test documented here specifically used:

``` text
gemma3-1b-it-int4.litertlm
```

------------------------------------------------------------------------

## 4. Minimum Android Project

The test project used a simple Android application with the package:

``` text
com.example.gemmaphonetest
```

The project only needed:

``` text
app
└── MainActivity
```

The screen contained:

``` text
Text Input
    ↓
Analyze Button
    ↓
Output Area
```

No other application features were required.

------------------------------------------------------------------------

## 5. Model Transfer to the Phone

After downloading the model, the model was copied to the Android device
using ADB.

### Check the device

Open the Android Studio Command Prompt or Windows CMD:

``` cmd
adb devices -l
```

The physical Xiaomi phone should appear as an authorized device.

### Push the model to temporary device storage

Example:

``` cmd
adb push "C:\path\to\gemma3-1b-it-int4.litertlm" /data/local/tmp/gemma3-1b-it-int4.litertlm
```

Replace the Windows path with the actual location of the downloaded
model.

### Copy the model into the app's private files directory

The test application package was:

``` text
com.example.gemmaphonetest
```

Run:

``` cmd
adb shell run-as com.example.gemmaphonetest cp /data/local/tmp/gemma3-1b-it-int4.litertlm files/gemma3-1b-it-int4.litertlm
```

### Verify the file

``` cmd
adb shell run-as com.example.gemmaphonetest ls -l files/gemma3-1b-it-int4.litertlm
```

A successful listing confirms that the model file exists inside the
application's private storage.

------------------------------------------------------------------------

## 6. Important: File Transfer Is Not the Inference Test

Successfully running:

``` cmd
adb shell run-as com.example.gemmaphonetest ls -l files/gemma3-1b-it-int4.litertlm
```

only confirms that the model file was copied successfully.

The actual test is:

``` text
Android app
    ↓
Load .litertlm model
    ↓
Initialize LiteRT-LM
    ↓
Send text prompt
    ↓
Run inference on the phone
    ↓
Receive generated text
    ↓
Display response
```

The test is considered successful only when the phone actually generates
a response.

------------------------------------------------------------------------

## 7. Test Prompt

Use this exact prompt:

``` text
Analyze this message: I cannot log in to my account even though my password is correct.
```

------------------------------------------------------------------------

## 8. Successful Test Output

The physical Xiaomi 11 Lite 5G NE successfully produced:

``` text
Category: Login Issue
Summary: The user cannot access their account despite entering the correct password.
```

This confirms that the tested configuration was able to:

1.  Load the Gemma model.
2.  Run inference locally on the Android phone.
3.  Process the supplied text.
4.  Generate a text response.
5.  Display the response in the Android application.

------------------------------------------------------------------------

## 9. What This Test Proves

The successful test demonstrates that:

``` text
Xiaomi 11 Lite 5G NE
        ↓
Android 14
        ↓
LiteRT-LM
        ↓
Gemma 3 1B IT INT4
        ↓
.litertlm model
        ↓
Local inference
        ↓
Generated response
```

works on the test device.

This means the phone is capable of running this particular Gemma 3 1B
INT4 LiteRT-LM configuration.

------------------------------------------------------------------------

## 10. What This Test Does NOT Prove

This test does **not** prove that the existing:

``` text
gemma3-1b-it-int4.task
```

implementation works.

It also does not prove that the original smishing detector has no
Gemma-related problems.

The two configurations are different:

### Successful test

``` text
Gemma 3 1B IT INT4
        ↓
.litertlm
        ↓
LiteRT-LM
        ↓
Android
```

### Original application

``` text
Gemma 3 1B IT INT4
        ↓
.task
        ↓
Existing Android inference implementation
        ↓
Smishing Detector
```

If the original `.task` implementation continues to fail while the
`.litertlm` test works, the problem should be investigated separately
rather than assuming the phone cannot run Gemma.

------------------------------------------------------------------------

## 11. Test Result

### Result: SUCCESS

The tested physical device successfully generated a Gemma response
using:

``` text
Model: gemma3-1b-it-int4.litertlm
Runtime: LiteRT-LM
Device: Xiaomi 11 Lite 5G NE
Android: 14
```

Successful response:

``` text
Category: Login Issue
Summary: The user cannot access their account despite entering the correct password.
```

------------------------------------------------------------------------

## 12. Do Not Change the Main Application Yet

This test was intended only to establish a known-working baseline.

The next step should be to compare the working LiteRT-LM implementation
with the existing `.task` implementation in the main smishing detector.

Do not replace the main application's model or rewrite its Gemma code
solely because this test succeeded.

First preserve the working test configuration, including:

-   model file
-   LiteRT-LM dependency/version
-   model-loading code
-   inference code
-   prompt format
-   Android configuration
-   device used for testing

This gives the project a known-good baseline for debugging the original
application.

------------------------------------------------------------------------

## 13. Quick Reproduction Checklist

``` text
[ ] Create a clean Android project
[ ] Connect Xiaomi 11 Lite 5G NE
[ ] Confirm ADB detects the phone
[ ] Download gemma3-1b-it-int4.litertlm
[ ] Accept the applicable Gemma license/terms
[ ] Install/run the test application
[ ] Push the model with ADB
[ ] Copy the model into the app's private files directory
[ ] Verify the model file exists
[ ] Load the model with LiteRT-LM
[ ] Enter the test prompt
[ ] Press Analyze
[ ] Confirm a Gemma response is generated
[ ] Record model load time
[ ] Record inference time
[ ] Record any errors/logcat output
```

------------------------------------------------------------------------

## 14. References

-   LiteRT Community Gemma 3 1B IT model repository:
    https://huggingface.co/litert-community/Gemma3-1B-IT

-   Google AI Edge LiteRT-LM: https://ai.google.dev/edge/litert-lm

-   LiteRT-LM CLI documentation:
    https://ai.google.dev/edge/litert-lm/cli

The model repository lists both `.litertlm` and `.task` artifacts. The
successful test documented in this file used the `.litertlm` artifact
with LiteRT-LM.
