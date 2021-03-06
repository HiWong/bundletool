/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.validation;

import static com.android.tools.build.bundletool.testing.ManifestProtoUtils.androidManifest;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.android.tools.build.bundletool.exceptions.ValidationException;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.testing.BundleModuleBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DexFilesValidatorTest {

  @Test
  public void noDexFiles_ok() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base").setManifest(androidManifest("com.test.app")).build();

    new DexFilesValidator().validateModule(module);
  }

  @Test
  public void singleDexFile_valid_ok() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base")
            .addFile("dex/classes.dex")
            .setManifest(androidManifest("com.test.app"))
            .build();

    new DexFilesValidator().validateModule(module);
  }

  @Test
  public void singleDexFile_invalid_throws() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base")
            .addFile("dex/classes2.dex")
            .setManifest(androidManifest("com.test.app"))
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new DexFilesValidator().validateModule(module));

    assertThat(exception)
        .hasMessageThat()
        .contains("expecting file 'classes.dex' but found 'classes2.dex'");
  }

  @Test
  public void manyDexFiles_valid_ok() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base")
            .addFile("dex/classes.dex")
            .addFile("dex/classes2.dex")
            .addFile("dex/classes3.dex")
            .addFile("dex/classes4.dex")
            .setManifest(androidManifest("com.test.app"))
            .build();

    new DexFilesValidator().validateModule(module);
  }

  @Test
  public void manyDexFiles_gap_throws() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base")
            .addFile("dex/classes.dex")
            .addFile("dex/classes2.dex")
            .addFile("dex/classes42.dex")
            .setManifest(androidManifest("com.test.app"))
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new DexFilesValidator().validateModule(module));

    assertThat(exception)
        .hasMessageThat()
        .contains("expecting file 'classes3.dex' but found 'classes42.dex'");
  }

  @Test
  public void singleDexFile_hasClasses1_throws() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base")
            .addFile("dex/classes1.dex")
            .setManifest(androidManifest("com.test.app"))
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new DexFilesValidator().validateModule(module));

    assertThat(exception)
        .hasMessageThat()
        .contains("expecting file 'classes.dex' but found 'classes1.dex'");
  }

  @Test
  public void manyDexFiles_hasClasses1_throws() throws Exception {
    BundleModule module =
        new BundleModuleBuilder("base")
            .addFile("dex/classes.dex")
            .addFile("dex/classes1.dex")
            .addFile("dex/classes2.dex")
            .setManifest(androidManifest("com.test.app"))
            .build();

    ValidationException exception =
        assertThrows(
            ValidationException.class, () -> new DexFilesValidator().validateModule(module));

    assertThat(exception)
        .hasMessageThat()
        .contains("expecting file 'classes2.dex' but found 'classes1.dex'");
  }
}
