#  Copyright 2020 Accenture Global Solutions Limited
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import itertools as it
import re
import typing as tp
import pathlib
import logging

import google.protobuf.descriptor_pb2 as pb_desc
import google.protobuf.compiler.plugin_pb2 as pb_plugin


class LocationContext:

    def __init__(self, src_locations: tp.List[pb_desc.SourceCodeInfo.Location],
                 src_loc_code: int, src_loc_index: int, indent: int):

        self.src_locations = src_locations
        self.src_loc_code = src_loc_code
        self.src_loc_index = src_loc_index
        self.indent = indent

    def for_index(self, index: int) -> 'LocationContext':

        return LocationContext(self.src_locations, self.src_loc_code, index, self.indent)


class TracGenerator:
    
    _FieldType = pb_desc.FieldDescriptorProto.Type

    PROTO_TYPE_MAPPING = dict({

        _FieldType.TYPE_DOUBLE: float,
        _FieldType.TYPE_FLOAT: float,
        _FieldType.TYPE_INT64: int,
        _FieldType.TYPE_UINT64: int,
        _FieldType.TYPE_INT32: int,
        _FieldType.TYPE_FIXED64: int,
        _FieldType.TYPE_FIXED32: int,
        _FieldType.TYPE_BOOL: bool,
        _FieldType.TYPE_STRING: str,
        
        # Group type is deprecated and not supported in proto3
        # _FieldType.TYPE_GROUP
        
        # Do not include a mapping for message type, it will be handle specially
        # _FieldType.TYPE_MESSAGE

        _FieldType.TYPE_BYTES: bytes,  # TODO: Use bytearray?
        
        _FieldType.TYPE_UINT32: int,

        # Do not include a mapping for enum type, it will be handle specially
        # _FieldType.TYPE_ENUM

        _FieldType.TYPE_SFIXED32: int,
        _FieldType.TYPE_SFIXED64: int,
        _FieldType.TYPE_SINT32: int,
        _FieldType.TYPE_SINT64: int
    })

    INDENT_TEMPLATE = " " * 4

    PACKAGE_IMPORT_TEMPLATE = "from .{MODULE_NAME} import {SYMBOL}\n"

    FILE_TEMPLATE = \
        """# Code generated by TRAC\n""" \
        """\n""" \
        """{IMPORT_STATEMENTS}\n""" \
        """\n""" \
        """{ENUMS_CODE}\n""" \
        """{MESSAGES_CODE}\n"""

    MESSAGE_TEMPLATE = \
        """{INDENT}\n""" \
        """{INDENT}class {CLASS_NAME}:\n""" \
        """{NEXT_INDENT}\n""" \
        """{NEXT_INDENT}\"\"\"\n""" \
        """{DOC_COMMENT}\n""" \
        """{NEXT_INDENT}\"\"\"\n""" \
        """{NEXT_INDENT}\n""" \
        """{NESTED_ENUMS}""" \
        """{NESTED_MESSAGES}""" \
        """{INIT_METHOD}\n"""

    INIT_METHOD_TEMPLATE = \
        """{INDENT}def __init__(self{INIT_PARAMS}):{PEP_FLAG}\n""" \
        """{NEXT_INDENT}\n""" \
        """{INIT_VARS}\n"""

    INIT_PARAM_TEMPLATE = \
        ",{PEP_FLAG}\n{INDENT}{PARAM_NAME}: {PARAM_TYPE}"

    INIT_VAR_TEMPLATE = \
        "{INDENT}self.{IVAR_NAME} = {PARAM_NAME}\n" \
        "{IVAR_COMMENT}"

    INIT_PASS_TEMPLATE = \
        "{INDENT}pass\n"

    ENUM_TEMPLATE = \
        """{INDENT}\n""" \
        """{INDENT}class {CLASS_NAME}(enum.Enum):\n""" \
        """{NEXT_INDENT}\n""" \
        """{NEXT_INDENT}\"\"\"\n""" \
        """{DOC_COMMENT}\n""" \
        """{NEXT_INDENT}\"\"\"\n""" \
        """{INDENT}\n""" \
        """{ENUM_VALUES}\n"""

    ENUM_VALUE_TEMPLATE = \
        """{INDENT}{ENUM_VALUE_NAME} = {ENUM_VALUE_NUMBER}, {QUOTED_COMMENT}\n"""

    INLINE_COMMENT_SINGLE_LINE = \
        '\n{INDENT}"""{COMMENT}"""\n' \

    INLINE_COMMENT_MULTI_LINE = \
        '\n{INDENT}"""\n' \
        '{INDENT}{COMMENT}\n' \
        '{INDENT}"""\n'

    ENUM_COMMENT_SINGLE_LINE = \
        '"""{COMMENT}"""'

    ENUM_COMMENT_MULTI_LINE = \
        '"""{COMMENT}\n' \
        '{INDENT}"""'

    def __init__(self):

        logging.basicConfig(level=logging.DEBUG)
        self._log = logging.getLogger(TracGenerator.__name__)

        self._enum_type_field = self.get_field_number(pb_desc.FileDescriptorProto, "enum_type")
        self._message_type_field = self.get_field_number(pb_desc.FileDescriptorProto, "message_type")
        self._message_field_field = self.get_field_number(pb_desc.DescriptorProto, "field")
        self._enum_value_field = self.get_field_number(pb_desc.EnumDescriptorProto, "value")

    def generate_package(self, package: str, files: tp.List[pb_desc.FileDescriptorProto]) \
            -> tp.List[pb_plugin.CodeGeneratorResponse.File]:

        output_files = []

        # Use the protobuf package as the Python package
        package_path = pathlib.Path(*package.split("."))
        package_imports = ""

        for file_descriptor in files:

            # Run the generator to produce code for the Python module
            src_locations = file_descriptor.source_code_info.location
            file_code = self.generate_file(src_locations, 0, file_descriptor)

            # Find the module name inside the package - this is the stem of the .proto file
            file_path = pathlib.PurePath(file_descriptor.name)
            file_stem = file_path.stem

            # Create a generator response for the module
            file_response = pb_plugin.CodeGeneratorResponse.File()
            file_response.content = file_code

            # File name is formed from the python package and the module name (.proto file stem)
            file_response.name = str(package_path.joinpath(file_stem + ".py"))

            output_files.append(file_response)

            # Generate import statements to include in the package-level __init__ file
            package_imports += self.generate_package_imports(file_descriptor)

        # Add an extra generator response file for the package-level __init__ file
        package_init_file = pb_plugin.CodeGeneratorResponse.File()
        package_init_file.name = str(package_path.joinpath("__init__.py"))
        package_init_file.content = package_imports

        output_files.append(package_init_file)

        return output_files

    def generate_package_imports(self, descriptor: pb_desc.FileDescriptorProto) -> str:

        file_path = pathlib.Path(descriptor.name)
        module_name = file_path.stem

        imports = ""

        if len(descriptor.enum_type) > 0 or len(descriptor.message_type) > 0:
            imports += "\n"

        for enum_type in descriptor.enum_type:
            imports += self.PACKAGE_IMPORT_TEMPLATE.format(
                MODULE_NAME=module_name,
                SYMBOL=enum_type.name)

        for message_type in descriptor.message_type:
            imports += self.PACKAGE_IMPORT_TEMPLATE.format(
                MODULE_NAME=module_name,
                SYMBOL=message_type.name)

        return imports

    def generate_file(self, src_loc, indent: int, descriptor: pb_desc.FileDescriptorProto) -> str:

        # print(descriptor.name)
        # self._log.info(descriptor.name)

        imports = []
        imports.append("import typing as tp")

        if len(descriptor.enum_type) > 0:
            imports.append("import enum")

        # Generate imports
        for import_proto in descriptor.dependency:
            if import_proto.startswith("trac/metadata/"):
                import_module = import_proto \
                    .replace("trac/metadata/", "") \
                    .replace("/", ".") \
                    .replace(".proto", "")
                imports.append("from .{} import *".format(import_module))

        # Generate enums
        enum_ctx = self.index_sub_ctx(src_loc, self._enum_type_field, indent)
        enum_code = list(it.starmap(self.generate_enum, zip(enum_ctx, descriptor.enum_type)))

        # Generate message classes
        message_ctx = self.index_sub_ctx(src_loc, self._message_type_field, indent)
        message_code = list(it.starmap(self.generate_message, zip(message_ctx, descriptor.message_type)))

        # Populate the template
        code = self.FILE_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * indent) \
            .replace("{IMPORT_STATEMENTS}", "\n".join(imports)) \
            .replace("{ENUMS_CODE}", "\n\n".join(enum_code)) \
            .replace("{MESSAGES_CODE}", "\n\n".join(message_code))

        return code

    def generate_message(self, ctx: LocationContext, descriptor: pb_desc.DescriptorProto) -> str:

        # Generate comments
        filtered_loc = self.filter_src_location(ctx.src_locations, ctx.src_loc_code, ctx.src_loc_index)
        raw_comment = self.comment_for_current_location(filtered_loc)
        formatted_comment = self.comment_block_translation(ctx, raw_comment)

        # Generate nested enums
        enum_ctx = self.index_sub_ctx(filtered_loc, self._enum_type_field, ctx.indent + 1)
        enum_code = list(it.starmap(self.generate_enum, zip(enum_ctx, descriptor.enum_type)))

        # Generate nested message classes
        message_ctx = self.index_sub_ctx(filtered_loc, self._message_type_field, ctx.indent + 1)
        message_code = list(it.starmap(self.generate_message, zip(message_ctx, descriptor.nested_type)))

        # Generate init
        init_ctx = LocationContext(filtered_loc, ctx.src_loc_code, ctx.src_loc_index, ctx.indent + 1)
        init_method = self.generate_init_method(init_ctx, descriptor)

        return self.MESSAGE_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
            .replace("{NEXT_INDENT}", self.INDENT_TEMPLATE * (ctx.indent + 1)) \
            .replace("{CLASS_NAME}", descriptor.name) \
            .replace("{DOC_COMMENT}", formatted_comment) \
            .replace("{NESTED_ENUMS}", "\n".join(enum_code)) \
            .replace("{NESTED_MESSAGES}", "\n".join(message_code)) \
            .replace("{INIT_METHOD}", init_method)

    def generate_init_method(self, ctx: LocationContext, descriptor: pb_desc.DescriptorProto) -> str:

        fields_ctx = self.index_sub_ctx(ctx.src_locations, self._message_field_field, ctx.indent + 2)
        params_iter = it.starmap(self.generate_init_param, zip(fields_ctx, descriptor.field, it.repeat(descriptor)))

        fields_ctx = self.index_sub_ctx(ctx.src_locations, self._message_field_field, ctx.indent + 1)
        vars_iter = it.starmap(self.generate_init_var, zip(fields_ctx, descriptor.field))
        vars_pass = self.INIT_PASS_TEMPLATE.replace("{INDENT}", self.INDENT_TEMPLATE * (ctx.indent + 1))

        init_params = "".join(params_iter) if len(descriptor.field) > 0 else ""
        init_vars = "\n".join(vars_iter) if len(descriptor.field) > 0 else vars_pass

        # Do not apply the PEP flag if there are no parameters
        pep_flag = "  # noqa" if len(descriptor.field) > 0 else ""

        return self.INIT_METHOD_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
            .replace("{NEXT_INDENT}", self.INDENT_TEMPLATE * (ctx.indent + 1)) \
            .replace("{INIT_PARAMS}", init_params) \
            .replace("{INIT_VARS}", init_vars) \
            .replace("{PEP_FLAG}", pep_flag)

    def generate_init_param(self, ctx: LocationContext, descriptor: pb_desc.FieldDescriptorProto,
                            message: pb_desc.DescriptorProto):

        field_index = ctx.src_loc_index

        # Do not apply the PEP flag before the first parameter (i.e. against the 'self' parameter)
        pep_flag = "  # noqa" if field_index > 0 else ""

        field_type = self.python_field_type(descriptor, message)

        # Make all fields optional for now
        field_type = "tp.Optional[" + field_type + "] = None"

        # TODO: For dict and list types, use an empty container
        # Since minimum Python version for TRAC will now be 3.7,
        # We can change the generator to output dataclasses
        # For the time being, this implementation allows work on the engine to proceed

        return self.INIT_PARAM_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
            .replace("{PEP_FLAG}", pep_flag) \
            .replace("{PARAM_NAME}", descriptor.name) \
            .replace("{PARAM_TYPE}", field_type)

    def generate_init_var(self, ctx: LocationContext, descriptor: pb_desc.FieldDescriptorProto):

        filtered_loc = self.filter_src_location(ctx.src_locations, ctx.src_loc_code, ctx.src_loc_index)
        raw_comment = self.comment_for_current_location(filtered_loc)
        formatted_comment = self.comment_inline_translation(ctx, raw_comment)

        return self.INIT_VAR_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
            .replace("{PARAM_NAME}", descriptor.name) \
            .replace("{IVAR_NAME}", descriptor.name) \
            .replace("{IVAR_COMMENT}", formatted_comment)
    
    def python_field_type(self, descriptor: pb_desc.FieldDescriptorProto, message: pb_desc.DescriptorProto):

        base_type = self.python_base_type(descriptor)

        if descriptor.label == descriptor.Label.LABEL_REPEATED:

            sub_type_pattern = re.compile("\'{}\\.(.*)\'".format(message.name))
            sub_type_match = sub_type_pattern.match(base_type)

            if sub_type_match:

                sub_type = sub_type_match.group(1)
                sub_descriptor = next(filter(lambda msg: msg.name == sub_type, message.nested_type))
                key_type = self.python_base_type(sub_descriptor.field[0])
                value_type = self.python_base_type(sub_descriptor.field[1])

                return "tp.Dict[{}, {}]".format(key_type, value_type)

            else:

                return "tp.List[{}]".format(base_type)

        else:
            return base_type

    def python_base_type(self, descriptor: pb_desc.FieldDescriptorProto):

        # Messages (classes) and enums use the type name declared in the field
        if descriptor.type == descriptor.Type.TYPE_MESSAGE or descriptor.type == descriptor.Type.TYPE_ENUM:

            type_name = descriptor.type_name
            relative_name = type_name.replace(".trac.metadata.", "", 1)

            # Quote all object type names for now
            # Types that are already declared or imported could be hinted without quotes
            # This would require building a map of type names and tracking which ones are already declared
            # Quoted names just work everywhere!
            # There is no integrity check, but, protoc will already do this

            return "'{}'".format(relative_name)

        # For built in types, use a static mapping of proto type names
        if descriptor.type in self.PROTO_TYPE_MAPPING:

            return self.PROTO_TYPE_MAPPING[descriptor.type].__name__

        # Any unrecognised type is an error
        raise RuntimeError(
            "Unknown type in protobuf field descriptor: field = {}, type code = {}"
                .format(descriptor.name, descriptor.type))


    def generate_enum(self, ctx: LocationContext, descriptor: pb_desc.EnumDescriptorProto) -> str:

        filtered_loc = self.filter_src_location(ctx.src_locations, ctx.src_loc_code, ctx.src_loc_index)

        # Generate enum values
        values_ctx = self.index_sub_ctx(filtered_loc, self._enum_value_field, ctx.indent + 1)
        values_code = list(it.starmap(self.generate_enum_value, zip(values_ctx, descriptor.value)))

        raw_comment = self.comment_for_current_location(filtered_loc)
        formatted_comment = self.comment_block_translation(ctx, raw_comment)

        # Populate the template
        code = self.ENUM_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
            .replace("{NEXT_INDENT}", self.INDENT_TEMPLATE * (ctx.indent + 1)) \
            .replace("{DOC_COMMENT}", formatted_comment) \
            .replace("{CLASS_NAME}", descriptor.name) \
            .replace("{ENUM_VALUES}", "\n".join(values_code))

        return code

    def generate_enum_value(self, ctx: LocationContext, descriptor: pb_desc.EnumValueDescriptorProto) -> str:

        filtered_loc = self.filter_src_location(ctx.src_locations, ctx.src_loc_code, ctx.src_loc_index)

        # Comments from current code location
        raw_comment = self.comment_for_current_location(filtered_loc)
        formatted_comment = self.comment_enum_translation(ctx, raw_comment)

        # Populate the template
        code = self.ENUM_VALUE_TEMPLATE \
            .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
            .replace("{QUOTED_COMMENT}", formatted_comment) \
            .replace("{ENUM_VALUE_NAME}", descriptor.name) \
            .replace("{ENUM_VALUE_NUMBER}", str(descriptor.number))

        return code

    # Helpers

    def filter_src_location(self, locations, loc_type, loc_index):

        def relative_path(loc: pb_desc.SourceCodeInfo.Location):

            return pb_desc.SourceCodeInfo.Location(
                path=loc.path[2:], span=loc.span,
                leading_comments=loc.leading_comments,
                trailing_comments=loc.trailing_comments,
                leading_detached_comments=loc.leading_detached_comments)

        filtered = filter(lambda l: len(l.path) >= 2 and l.path[0] == loc_type and l.path[1] == loc_index, locations)
        return list(map(relative_path, filtered))

    def current_location(self, locations) -> pb_desc.SourceCodeInfo.Location:

        return next(filter(lambda l: len(l.path) == 0, locations), None)

    def comment_for_current_location(self, locations) -> tp.Optional[str]:

        # Comments from current code location
        current_loc = self.current_location(locations)

        if current_loc is not None:
            return current_loc.leading_comments
        else:
            return None

    def comment_block_translation(self, ctx: LocationContext, comment: tp.Optional[str]) -> tp.Optional[str]:

        if comment is None:
            return ""

        translated_comment = re.sub("^(\\*\n)|/", "", comment, count=1)
        translated_comment = re.sub("\n$", "", translated_comment)
        translated_comment = re.sub("^ ?", self.INDENT_TEMPLATE * (ctx.indent + 1), translated_comment)
        translated_comment = re.sub("\\n ?", "\n" + self.INDENT_TEMPLATE * (ctx.indent + 1), translated_comment)

        if translated_comment.strip() == "":
            return ""

        return translated_comment

    def comment_inline_translation(self, ctx: LocationContext, comment: tp.Optional[str]) -> tp.Optional[str]:

        if comment is None:
            return ''

        translated_comment = re.sub("^(\\*\n)|/", "", comment, count=1)
        translated_comment = re.sub("\n$", "", translated_comment)
        translated_comment = re.sub("^ ?", "", translated_comment)
        translated_comment = re.sub("\\n ?", "\n" + self.INDENT_TEMPLATE * ctx.indent, translated_comment)

        if translated_comment.strip() == "":
            return ''

        elif "\n" not in translated_comment.strip():
            return self.INLINE_COMMENT_SINGLE_LINE \
                .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
                .replace("{COMMENT}", translated_comment.strip())

        else:
            return self.INLINE_COMMENT_MULTI_LINE \
                .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
                .replace("{COMMENT}", translated_comment)

    def comment_enum_translation(self, ctx: LocationContext, comment: tp.Optional[str]) -> tp.Optional[str]:

        if comment is None:
            return ''

        translated_comment = re.sub("^(\\*\n)|/", "", comment, count=1)
        translated_comment = re.sub("\n$", "", translated_comment)
        translated_comment = re.sub("^ ?", "", translated_comment)
        translated_comment = re.sub("\\n ?", "\n" + self.INDENT_TEMPLATE * (ctx.indent + 1), translated_comment)

        if translated_comment.strip() == "":
            return ''

        elif "\n" not in translated_comment.strip():
            return self.ENUM_COMMENT_SINGLE_LINE \
                .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
                .replace("{COMMENT}", translated_comment.strip())

        else:
            return self.ENUM_COMMENT_MULTI_LINE \
                .replace("{INDENT}", self.INDENT_TEMPLATE * ctx.indent) \
                .replace("{COMMENT}", translated_comment)


    def index_sub_ctx(self, src_locations, field_number, indent):

        base_ctx = LocationContext(src_locations, field_number, 0, indent)
        return iter(map(base_ctx.for_index, it.count(0)))

    def indent_sub_ctx(self, ctx: LocationContext, indent: int):

        return LocationContext(ctx.src_locations, ctx.src_loc_code, ctx.src_loc_index, ctx.indent + indent)

    def get_field_number(self, message_descriptor, field_name: str):

        field_descriptor = next(filter(
            lambda f: f.name == field_name,
            message_descriptor.DESCRIPTOR.fields), None)

        if field_descriptor is None:

            # TODO: Debug code
            for field in message_descriptor.DESCRIPTOR.fields:
                print(field.name)

            raise RuntimeError("Field {} not found in type {}".format(field_name, message_descriptor.DESCRIPTOR.name))

        return field_descriptor.number