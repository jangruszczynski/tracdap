#  Copyright 2022 Accenture Global Solutions Limited
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

from __future__ import annotations

import html.parser
import json.decoder
import re
import subprocess
import subprocess as sp
import urllib.parse
import time
import zipfile
import io

import requests

import tracdap.rt.metadata as _meta
import tracdap.rt.config as _cfg
import tracdap.rt.exceptions as _ex
import tracdap.rt._impl.util as _util

# Import repo interfaces
from tracdap.rt.ext.repos import *


# Helper functions for handling credentials supplied via HTTP(S) URLs

__REPO_TOKEN_KEY = "token"
__REPO_USER_KEY = "username"
__REPO_PASS_KEY = "password"


def _get_credentials(url: urllib.parse.ParseResult, plugin_config: _cfg.PluginConfig):

    token = _util.get_plugin_property(plugin_config, __REPO_TOKEN_KEY)
    username = _util.get_plugin_property(plugin_config, __REPO_USER_KEY)
    password = _util.get_plugin_property(plugin_config, __REPO_PASS_KEY)

    if token is not None:
        return token

    if username is not None and password is not None:
        return f"{username}:{password}"

    if url.username:
        credentials_sep = url.netloc.index("@")
        return url.netloc[:credentials_sep]

    return None


def _apply_credentials(url: urllib.parse.ParseResult, credentials: str):

    if credentials is None:
        return url

    if url.username is None:
        location = f"{credentials}@{url.netloc}"

    else:
        location_sep = url.netloc.index("@")
        location = f"{credentials}@{url.netloc[location_sep + 1:]}"

    return url._replace(netloc=location)


class IntegratedSource(IModelRepository):

    def __init__(self, repo_config: _cfg.PluginConfig):
        self._repo_config = repo_config

    def checkout_key(self, model_def: _meta.ModelDefinition):
        return "trac_integrated"

    def package_path(
            self, model_def: _meta.ModelDefinition,
            checkout_dir: pathlib.Path) -> tp.Optional[pathlib.Path]:

        return None

    def do_checkout(
            self, model_def: _meta.ModelDefinition,
            checkout_dir: tp.Union[str, pathlib.Path]) \
            -> None:

        # For the integrated repo there is nothing to check out

        return self.package_path(model_def, checkout_dir)


class LocalRepository(IModelRepository):

    REPO_URL_KEY = "repoUrl"

    def __init__(self, repo_config: _cfg.PluginConfig):
        self._repo_config = repo_config
        self._repo_url = _util.get_plugin_property(self._repo_config, self.REPO_URL_KEY)

        if not self._repo_url:
            raise _ex.EConfigParse(f"Missing required property [{self.REPO_URL_KEY}] in local repository config")

    def checkout_key(self, model_def: _meta.ModelDefinition):
        return "trac_local"

    def package_path(
            self, model_def: _meta.ModelDefinition,
            checkout_dir: pathlib.Path) -> tp.Optional[pathlib.Path]:

        checkout_path = pathlib.Path(self._repo_url).joinpath(model_def.path)

        return checkout_path

    def do_checkout(self, model_def: _meta.ModelDefinition, checkout_dir: pathlib.Path) -> pathlib.Path:

        # For local repos, checkout is a no-op since the model is already local
        # Just return the existing package path

        return self.package_path(model_def, checkout_dir)


class GitRepository(IModelRepository):

    REPO_URL_KEY = "repoUrl"
    GIT_TIMEOUT_SECONDS = 30

    def __init__(self, repo_config: _cfg.PluginConfig):

        self._repo_config = repo_config
        self._log = _util.logger_for_object(self)

        repo_url_prop = _util.get_plugin_property(self._repo_config, self.REPO_URL_KEY)

        if not repo_url_prop:
            raise _ex.EConfigParse(f"Missing required property [{self.REPO_URL_KEY}] in Git repository config")

        repo_url = urllib.parse.urlparse(repo_url_prop)
        credentials = _get_credentials(repo_url, repo_config)

        self._repo_url = _apply_credentials(repo_url, credentials)

    def checkout_key(self, model_def: _meta.ModelDefinition):
        return model_def.version

    def package_path(
            self, model_def: _meta.ModelDefinition,
            checkout_dir: pathlib.Path) -> tp.Optional[pathlib.Path]:

        return checkout_dir.joinpath(model_def.path)

    def do_checkout(self, model_def: _meta.ModelDefinition, checkout_dir: pathlib.Path) -> pathlib.Path:

        self._log.info(
            f"Git checkout: repo = [{model_def.repository}], " +
            f"group = [{model_def.packageGroup}], package = [{model_def.package}], version = [{model_def.version}]")

        self._log.info(f"Checkout location: [{checkout_dir}]")

        git_cli = ["git", "-C", str(checkout_dir)]

        git_cmds = [
            ["init"],
            ["remote", "add", "origin", self._repo_url.geturl()],
            ["fetch", "--depth=1", "origin", model_def.version],
            ["reset", "--hard", "FETCH_HEAD"]]

        # Work around Windows issues
        if _util.is_windows():

            # Some machines may still be setup without long path support in Windows and/or the Git client
            # Workaround: Enable the core.longpaths flag for each individual Git command (do not rely on system config)
            git_cli += ["-c", "core.longpaths=true"]

            # On some systems, directories created by the TRAC runtime process may not be owned by the process owner
            # This will cause Git to report an unsafe repo directory
            # Finding the current owner requires either batch scripting or using the win32_api package
            # Workaround: Explicitly take ownership of the repo directory, always, before starting the checkout
            try:
                self._log.info(f"Fixing filesystem permissions for [{checkout_dir}]")
                subprocess.run(f"takeown /f \"{checkout_dir}\"")
            except Exception:  # noqa
                self._log.info(f"Failed to fix filesystem permissions, this might prevent checkout from succeeding")

        for git_cmd in git_cmds:

            safe_cmd = map(_util.log_safe, git_cmd)
            self._log.info(f"git {' '.join(safe_cmd)}")

            cmd = [*git_cli, *git_cmd]
            cmd_result = sp.run(cmd, cwd=checkout_dir, stdout=sp.PIPE, stderr=sp.PIPE, timeout=self.GIT_TIMEOUT_SECONDS)

            if cmd_result.returncode != 0:
                time.sleep(1)
                self._log.warning(f"git {' '.join(git_cmd)} (retrying)")
                cmd_result = sp.run(cmd, cwd=checkout_dir, stdout=sp.PIPE, stderr=sp.PIPE, timeout=self.GIT_TIMEOUT_SECONDS)  # noqa

            cmd_out = str(cmd_result.stdout, 'utf-8').splitlines()
            cmd_err = str(cmd_result.stderr, 'utf-8').splitlines()

            for line in cmd_out:
                self._log.info(line)

            if cmd_result.returncode == 0:
                for line in cmd_err:
                    self._log.info(line)

            else:
                for line in cmd_err:
                    self._log.error(line)

                error_msg = f"Git checkout failed for {model_def.package} {model_def.version}"
                self._log.error(error_msg)
                raise _ex.EModelRepo(error_msg)

        self._log.info(f"Git checkout succeeded for {model_def.package} {model_def.version}")

        return self.package_path(model_def, checkout_dir)


class PyPiRepository(IModelRepository):

    SIMPLE_PACKAGE_PATH = "{}/{}/"
    JSON_PACKAGE_PATH = "{}/{}/{}/json"

    PIP_INDEX_KEY = "pipIndex"
    PIP_INDEX_URL_KEY = "pipIndexUrl"

    PIP_SIMPLE_FORMAT_KEY = "pipSimpleFormat"
    PIP_SIMPLE_FORMAT_JSON = "json"
    PIP_SIMPLE_FORMAT_HTML = "html"
    PIP_SIMPLE_FORMAT_DEFAULT = PIP_SIMPLE_FORMAT_JSON

    PIP_SIMPLE_CONTENT_TYPES = {
        PIP_SIMPLE_FORMAT_JSON: "application/vnd.pypi.simple.v1+json",
        PIP_SIMPLE_FORMAT_HTML: "text/html"
    }

    PIP_SIMPLE_TYPE_JSON = "application/vnd.pypi.simple.v1+json"
    PIP_SIMPLE_TYPE_HTML = "text/html"

    def __init__(self, repo_config: _cfg.PluginConfig):

        self._log = _util.logger_for_object(self)

        self._repo_config = repo_config

        self._pip_index = _util.get_plugin_property(self._repo_config, self.PIP_INDEX_KEY)
        self._pip_index_url = _util.get_plugin_property(self._repo_config, self.PIP_INDEX_URL_KEY)
        self._pip_simple_format = _util.get_plugin_property(self._repo_config, self.PIP_SIMPLE_FORMAT_KEY)

        if self._pip_index is None and self._pip_index_url is None:
            message = f"Neither [{self.PIP_INDEX_KEY}] nor [{self.PIP_INDEX_URL_KEY} is set in PyPi repository config"
            raise _ex.EConfigParse(message)

    def checkout_key(self, model_def: _meta.ModelDefinition):
        return model_def.version

    def package_path(
            self, model_def: _meta.ModelDefinition,
            checkout_dir: pathlib.Path) -> tp.Optional[pathlib.Path]:

        return checkout_dir

    def do_checkout(self, model_def: _meta.ModelDefinition, checkout_dir: pathlib.Path) -> tp.Optional[pathlib.Path]:

        self._log.info(
            f"PyPI checkout: repo = [{model_def.repository}], " +
            f"package = [{model_def.package}], version = [{model_def.version}]")

        self._log.info(f"Checkout location: [{checkout_dir}]")

        if self._pip_index_url is not None:
            package_filename, package_url = self._pypi_simple_query(model_def)

        else:
            package_filename, package_url = self._pypi_json_query(model_def)

        self._log.info(f"Downloading [{package_filename}]")
        self._log.info(f"GET: {_util.log_safe_url(package_url)}")

        download_req = requests.get(package_url.geturl())
        content = download_req.content
        elapsed = download_req.elapsed

        self._log.info(f"Downloaded [{len(content) / 1024:.1f}] KB in [{elapsed.total_seconds():.1f}] seconds")

        download_whl = zipfile.ZipFile(io.BytesIO(download_req.content))
        download_whl.extractall(checkout_dir)

        self._log.info(f"Unpacked [{len(download_whl.filelist)}] files")
        self._log.info(f"PyPI checkout succeeded for {model_def.package} {model_def.version}")

        return self.package_path(model_def, checkout_dir)

    def _pypi_simple_query(self, model_def: _meta.ModelDefinition):

        # PEP describing PyPI simple protocol
        # https://peps.python.org/pep-0691/

        try:

            simple_root_url = urllib.parse.urlparse(self._pip_index_url)
            simple_content_type = self._pypi_simple_content_type()
            simple_headers = {"accept": simple_content_type}

            credentials = _get_credentials(simple_root_url, self._repo_config)

            self._log.info(f"Query package: [{model_def.package}]")

            package_req = self._pypi_package_query(
                self.SIMPLE_PACKAGE_PATH, simple_root_url, simple_headers,
                credentials, model_def)

            received_content_type = package_req.headers.get("content-type")

            if received_content_type == self.PIP_SIMPLE_TYPE_JSON:
                filename, url = self._pypi_simple_parse_response(model_def, package_req.json())

            elif received_content_type == self.PIP_SIMPLE_TYPE_HTML:
                package_parser = _PypiSimpleHtmlParser(model_def.package, package_req.url)
                package_parser.feed(package_req.text)
                filename, url = self._pypi_simple_parse_response(model_def, package_parser.response)

            else:
                err = f"Invalid response from package repository: Content type = [{received_content_type}]"
                self._log.error(err)
                raise _ex.EModelRepo(err)

            package_url = urllib.parse.urlparse(url)
            package_url = _apply_credentials(package_url, credentials)

            return filename, package_url

        except json.JSONDecodeError as e:
            msg = f"Invalid response from model repository: {str(e)}"
            self._log.error(msg)
            raise _ex.EModelRepo("Invalid response from model repository") from e

    def _pypi_simple_content_type(self):

        if self._pip_simple_format in self.PIP_SIMPLE_CONTENT_TYPES:
            return self.PIP_SIMPLE_CONTENT_TYPES[self._pip_simple_format]

        if self._pip_simple_format is None:
            return self.PIP_SIMPLE_CONTENT_TYPES[self.PIP_SIMPLE_FORMAT_DEFAULT]

        self._log.warning(f"Unknown PyPI format [{self._pip_simple_format}], using [{self.PIP_SIMPLE_FORMAT_DEFAULT}]")

        return self.PIP_SIMPLE_CONTENT_TYPES[self.PIP_SIMPLE_FORMAT_DEFAULT]

    def _pypi_simple_parse_response(self, model_def, package_obj):

        package_name = package_obj.get("name") or model_def.package
        files = package_obj.get("files") or []

        name_pattern = package_name.replace("-", "_")  # dash is replaced by underscore in wheel names
        version_pattern = model_def.version.replace(".", "\\.")
        file_pattern = re.compile(f"^{name_pattern}-{version_pattern}-(?P<target>.*)\\.whl")

        matches = []

        for file_info in files:

            filename = file_info.get("filename") or ""
            yanked = file_info.get("yanked") or False
            match = file_pattern.match(filename)

            if match and not yanked:

                url = file_info.get("url")
                target = match.group("target")

                match_info = filename, url, target
                matches.append(match_info)

        if len(matches) == 0:
            message = f"No package found for [{package_name}] version [{model_def.version}]"
            self._log.error(message)
            raise _ex.EModelRepo(message)

        if len(matches) > 1:
            message = f"Multiple packages found for [{package_name}] version [{model_def.version}]" + \
                      f" (targets: " + ", ".join(map(lambda m: m[2], matches)) + ")"
            self._log.error(message)
            raise _ex.EModelRepo(message)

        filename, url, target = matches[0]

        self._log.info(f"Found package [{package_name}] version [{model_def.version}], target = [{target}]")

        return filename, url

    def _pypi_json_query(self, model_def: _meta.ModelDefinition):

        json_root_url = urllib.parse.urlparse(self._pip_index)
        json_headers = {"accept": "application/json"}

        credentials = _get_credentials(json_root_url, self._repo_config)

        self._log.info(f"Query package: [{model_def.package}], version = [{model_def.version}]")

        package_req = self._pypi_package_query(
            self.JSON_PACKAGE_PATH, json_root_url, json_headers,
            credentials, model_def)

        package_obj = package_req.json()
        package_info = package_obj.get("info") or {}
        summary = package_info.get("summary") or "(summary not available)"

        self._log.info(f"Package summary: {summary}")

        urls = package_obj.get("urls") or []
        bdist_urls = list(filter(lambda d: d.get("packagetype") == "bdist_wheel", urls))

        if not bdist_urls:
            message = "No compatible packages found"
            self._log.error(message)
            raise _ex.EModelRepo(message)

        if len(bdist_urls) > 1:
            message = "Multiple compatible packages found (specialized distributions are not supported yet)"
            self._log.error(message)
            raise _ex.EModelRepo(message)

        package_url_info = bdist_urls[0]
        package_filename = package_url_info.get("filename")
        package_url = urllib.parse.urlparse(package_url_info.get("url"))
        package_url = _apply_credentials(package_url, credentials)

        return package_filename, package_url

    def _pypi_package_query(self, package_path_template, root_url, headers, credentials, model_def):

        root_url = _apply_credentials(root_url, credentials)
        package_path = package_path_template.format(root_url.path, model_def.package, model_def.version)
        package_url = root_url._replace(path=package_path)

        self._log.info(f"GET: {_util.log_safe_url(package_url)}")

        package_req = requests.get(package_url.geturl(), headers=headers)

        if package_req.status_code != requests.codes.OK:
            message = f"Package lookup failed: [{package_req.status_code}] {package_req.reason}"
            self._log.error(message)
            raise _ex.EModelRepo(message)  # todo status code for access, not found etc

        return package_req


class _PypiSimpleHtmlParser(html.parser.HTMLParser):

    def __init__(self, package_name: str, package_url: str):

        super().__init__()

        self._package_url = package_url
        self._stack = []
        self._data = None

        # Empty response object as per PEP-691
        # https://peps.python.org/pep-0691/#project-detail

        self.response = {
            "meta": {"api-version": None},
            "name": package_name,
            "files": []
        }

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):

        element = (tag, attrs)
        self._stack.append(element)
        self._data = None

        if tag == "meta" and any(map(lambda a: a[0] == "name" and a[1] == "pypi:repository-version", attrs)):
            self._record_meta(attrs)

    def handle_endtag(self, tag: str):

        stack_tag, stack_attrs = self._stack.pop()

        if tag == "a" and stack_tag == "a" and self._data is not None:
            self._record_link(self._data, stack_attrs)

        while stack_tag != tag and len(self._stack) > 0:
            stack_tag, _ = self._stack.pop()

    def handle_data(self, data: str):

        stripped_data = data.strip()
        self._data = stripped_data if len(stripped_data) > 0 else None

    def _record_meta(self, attrs: list[tuple[str, str | None]]):

        content_attr = list(filter(lambda a: a[0] == "content", attrs))

        if len(content_attr) == 1:
            content, version = content_attr[0]
            self.response["meta"]["api-version"] = version

    def _record_link(self, filename, attrs: list[tuple[str, str | None]]):

        href_attr = list(filter(lambda a: a[0] == "href", attrs))
        yanked_attr = list(filter(lambda a: a[0] == "data-yanked", attrs))
        python_version_attr = list(filter(lambda a: a[0] == "data-requires-python", attrs))

        if len(href_attr) != 1:
            return

        _, href_url = href_attr[0]
        is_yanked = any(yanked_attr)
        _, required_python = python_version_attr[0] if len(python_version_attr) == 1 else (None, None)

        if "#" in href_url:
            sep = href_url.index("#")
            hash_info = href_url[sep + 1:].split("=")
            href_url = href_url[:sep]
        else:
            hash_info = None

        # URL in the HREF attribute can be absolute or relative
        file_url = urllib.parse.urljoin(self._package_url, href_url)

        if hash_info and len(hash_info) == 2:
            hashes = {hash_info[0]: hash_info[1]}
        else:
            hashes = {}

        file_info = {
            "filename": filename,
            "url": file_url,
            "hashes": hashes,
            "yanked": is_yanked
        }

        self.response["files"].append(file_info)


# TODO: Move Git and PyPI repos into _plugins


class RepositoryManager:

    __repo_types: tp.Dict[str, tp.Callable[[_cfg.PluginConfig], IModelRepository]] = {
        "integrated": IntegratedSource,
        "local": LocalRepository,
        "git": GitRepository,
        "pypi": PyPiRepository
    }

    @classmethod
    def register_repo_type(cls, repo_type: str, loader_class: tp.Callable[[_cfg.PluginConfig], IModelRepository]):
        cls.__repo_types[repo_type] = loader_class

    def __init__(self, sys_config: _cfg.RuntimeConfig):

        self._log = _util.logger_for_object(self)
        self._loaders: tp.Dict[str, IModelRepository] = dict()

        for repo_name, repo_config in sys_config.repositories.items():

            if repo_config.protocol not in self.__repo_types:

                msg = f"Model repository type [{repo_config.protocol}] is not recognised" \
                    + " (this could indicate a missing model repository plugin)"

                self._log.error(msg)
                raise _ex.EModelRepoConfig(msg)

            loader_class = self.__repo_types[repo_config.protocol]
            loader = loader_class(repo_config)
            self._loaders[repo_name] = loader

    def get_repository(self, repo_name: str) -> IModelRepository:

        loader = self._loaders.get(repo_name)

        if loader is None:

            msg = f"Model repository [{repo_name}] is unknown or not configured" \
                + " (this could indicate a missing repository entry in the system config)"

            self._log.error(msg)
            raise _ex.EModelRepoConfig(msg)

        return loader
