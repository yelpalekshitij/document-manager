import 'package:openapi_generator_annotations/openapi_generator_annotations.dart';

/// OpenAPI codegen configuration. `dart run build_runner build` reads the
/// annotation, runs the dart-dio generator against the spec, and writes the
/// resulting package into `<project>/api/`.
///
/// The generated package is consumed via the `vaultdoc_api` path dependency
/// declared in pubspec.yaml.
@Openapi(
  inputSpec: InputSpec(path: '../openapi/vaultdoc.yaml'),
  generatorName: Generator.dio,
  outputDirectory: 'api',
  cleanSubOutputDirectory: ['api'],
  additionalProperties: AdditionalProperties(
    pubName: 'vaultdoc_api',
    pubAuthor: 'VaultDoc',
    pubDescription: 'Generated VaultDoc API client. Do not edit by hand.',
  ),
)
class VaultDocApiConfig {}
