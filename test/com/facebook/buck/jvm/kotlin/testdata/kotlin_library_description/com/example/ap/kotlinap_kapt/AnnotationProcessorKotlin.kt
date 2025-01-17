package com.example.ap.kotlinap_kapt

import com.google.auto.service.AutoService
import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement

import com.example.ap.kotlinannotation.KotlinAnnotation
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec

@AutoService(Processor::class)
class AnnotationProcessorKotlin : AbstractProcessor() {

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(KotlinAnnotation::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
      roundEnv.getElementsAnnotatedWith(KotlinAnnotation::class.java).forEach {

          val className = it.simpleName.toString()
          val pkg = processingEnv.elementUtils.getPackageOf(it).toString()
          generateClass(className, pkg)
        }
      return true
    }

    private fun generateClass(name: String, pkg: String) {
        val genDir = processingEnv.options["kapt.kotlin.generated"]
        val fileName = "${name}_kaptgen"
        val file = FileSpec
                .builder(pkg, fileName)
                .addType(TypeSpec
                        .classBuilder(fileName)
                        .build()
                ).build()
        file.writeTo(File(genDir))
    }
}
