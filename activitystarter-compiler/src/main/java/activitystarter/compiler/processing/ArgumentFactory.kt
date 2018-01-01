package activitystarter.compiler.processing

import activitystarter.Arg
import activitystarter.compiler.error.Errors
import activitystarter.compiler.error.error
import activitystarter.compiler.model.ConverterModel
import activitystarter.compiler.model.ProjectConfig
import activitystarter.compiler.model.classbinding.KnownClassType
import activitystarter.compiler.model.param.ArgumentModel
import activitystarter.compiler.model.param.FieldAccessor
import activitystarter.compiler.model.param.ParamType
import activitystarter.compiler.utils.getElementType
import com.squareup.javapoet.TypeName
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.ExecutableType
import javax.lang.model.type.TypeMirror

class ArgumentFactory(val enclosingElement: TypeElement, val config: ProjectConfig) {

    fun create(element: Element, packageName: String, knownClassType: KnownClassType): ArgumentModel? = when (element.kind) {
        ElementKind.FIELD -> createFromField(element, packageName, knownClassType)
        ElementKind.METHOD -> createFromGetter(element, packageName, knownClassType)
        else -> null
    }

    private fun createFromField(element: Element, packageName: String, knownClassType: KnownClassType): ArgumentModel? {
        val elementType: TypeMirror = getElementType(element)
        val paramType = ParamType.fromType(elementType)
        val accessor: FieldAccessor = FieldAccessor.fromElement(element)
        val error = getFieldError(knownClassType, paramType, accessor)
        if (error != null) {
            showProcessingError(element, error)
            return null
        }

        val name: String = element.simpleName.toString()
        val annotation = element.getAnnotation(Arg::class.java)
        val keyFromAnnotation = annotation?.key
        val isParceler = annotation?.parceler ?: false
        val optional: Boolean = annotation?.optional ?: false

        val key: String = if (keyFromAnnotation.isNullOrBlank()) "$packageName.${name}StarterKey" else keyFromAnnotation!!
        val typeName: TypeName = TypeName.get(elementType)
        val (converter, saveParamType) = getConverterAndSaveType(isParceler, elementType, paramType, element) ?: return null
        return ArgumentModel(name, key, paramType, typeName, saveParamType, optional, accessor, converter, isParceler)
    }

    fun createFromGetter(getterElement: Element, packageName: String?, knownClassType: KnownClassType): ArgumentModel? {
        if (!getterElement.simpleName.startsWith("get")) {
            showProcessingError(getterElement, "Function is not getter")
            return null
        }

        val name = getterElement.simpleName.toString().substringAfter("get").decapitalize()
        val getter = getterElement.asType() as? ExecutableType
        if (getter == null) {
            showProcessingError(getterElement, "Type is not method")
            return null
        }

        val returnType: TypeMirror = getter.returnType
        val paramType = ParamType.fromType(returnType)
        val accessor: FieldAccessor = FieldAccessor.fromGetter(getterElement, name)

        val error = getGetterError(knownClassType, paramType, accessor)
        if (error != null) {
            showProcessingError(getterElement, error)
            return null
        }

        val annotation = getterElement.getAnnotation(Arg::class.java)
        val keyFromAnnotation = annotation?.key
        val parceler = annotation?.parceler ?: false
        val optional: Boolean = annotation?.optional ?: false

        val key: String = if (keyFromAnnotation.isNullOrBlank()) "$packageName.${name}StarterKey" else keyFromAnnotation!!
        val typeName: TypeName = TypeName.get(returnType)
        val (converter, saveParamType) = getConverterAndSaveType(parceler, returnType, paramType, getterElement) ?: return null
        return ArgumentModel(name, key, paramType, typeName, saveParamType, optional, accessor, converter, parceler)
    }

    private fun getConverterAndSaveType(isParceler: Boolean, elementType: TypeMirror, paramType: ParamType, element: Element): Pair<ConverterModel?, ParamType>? {
        if (isParceler) {
            return null to ParamType.ParcelableSubtype
        } else {
            val converter = config.converterFor(elementType)
            val saveParamType = converter?.toParamType ?: paramType
            if (saveParamType == ParamType.ObjectSubtype) {
                showProcessingError(element, Errors.notSupportedType.replace("{Type}", paramType.name).replace("{Field}", element.simpleName?.toString() ?: ""))
                return null
            }
            return converter to saveParamType
        }
    }

    private fun getFieldError(knownClassType: KnownClassType, paramTypeNullable: ParamType?, accessor: FieldAccessor) = when {
        enclosingElement.kind != ElementKind.CLASS -> Errors.notAClass
        enclosingElement.modifiers.contains(Modifier.PRIVATE) -> Errors.privateClass
        paramTypeNullable == null -> Errors.notSupportedType.replace("{Type}", paramTypeNullable?.name ?: "null").replace("{Field}", accessor.fieldName)
        !accessor.isAccessible() -> Errors.inaccessibleField
        paramTypeNullable.typeUsedBySupertype() && knownClassType == KnownClassType.BroadcastReceiver -> Errors.notBasicTypeInReceiver
        else -> null
    }

    private fun getGetterError(knownClassType: KnownClassType, paramTypeNullable: ParamType?, accessor: FieldAccessor) = when {
        enclosingElement.kind != ElementKind.CLASS -> Errors.notAClass
        enclosingElement.modifiers.contains(Modifier.PRIVATE) -> Errors.privateClass
        paramTypeNullable == null -> Errors.notSupportedType.replace("{Type}", paramTypeNullable?.name ?: "null").replace("{Field}", accessor.fieldName)
        paramTypeNullable.typeUsedBySupertype() && knownClassType == KnownClassType.BroadcastReceiver -> Errors.notBasicTypeInReceiver
        else -> null
    }

    private fun showProcessingError(element: Element, text: String) {
        error(enclosingElement, "@%s %s $text (%s)", Arg::class.java.simpleName, enclosingElement.qualifiedName, element.simpleName)
    }

    class ProcessingError(override val message: String) : Throwable(message)
}