"""Skills provider for loading local SKILL.md files."""

from __future__ import annotations

import re
import shutil
from dataclasses import dataclass
from pathlib import Path

import yaml

from ..constants import SKILLS_DIR
from ..exceptions import SkillLoadError

_FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n?(.*)$", re.DOTALL)


@dataclass(slots=True)
class Skill:
    name: str
    description: str
    content: str
    path: Path


class SkillsProvider:
    """Load all skills from fixed local directory."""

    def __init__(self, skills_dir: str | Path | None = None) -> None:
        self._skills_dir = Path(skills_dir or SKILLS_DIR)
        self._skills: list[Skill] = []
        self._skill_map: dict[str, Skill] = {}

    @property
    def skills_dir(self) -> Path:
        return self._skills_dir

    def load(self) -> list[Skill]:
        if not self._skills_dir.exists() or not self._skills_dir.is_dir():
            self._skills = []
            self._skill_map = {}
            return []

        loaded: list[Skill] = []
        for child in sorted(self._skills_dir.iterdir()):
            if not child.is_dir():
                continue
            skill_file = child / "SKILL.md"
            if not skill_file.exists():
                continue
            loaded.append(self._load_single(skill_file))

        if not loaded:
            self._skills = []
            self._skill_map = {}
            return []

        self._skills = loaded
        self._skill_map = {skill.name: skill for skill in loaded}
        return list(self._skills)

    def get_skill_summary(self) -> str:
        if not self._skills:
            return ""
        skill_names = self.get_skill_names()
        lines = ["## Available Skills"]
        if skill_names:
            lines.append(
                "Canonical skill names (exact match): "
                + ", ".join(skill_names),
            )
            lines.append(
                "If a requested skill exactly matches one of the names above, "
                "it is available.",
            )
        for skill in self._skills:
            lines.append(f"- {skill.name}: {skill.description}")
        return "\n".join(lines)

    def get_skill_names(self) -> list[str]:
        # Stable, deduplicated names for logging and prompt diagnostics.
        return sorted({skill.name for skill in self._skills})

    def get_skills(self) -> list[Skill]:
        return list(self._skills)

    def get_plugins_config(self) -> list[dict[str, str]]:
        return [
            {
                "type": "local",
                "path": str(skill.path.parent),
            }
            for skill in self._skills
        ]

    def get_setting_sources_config(self) -> list[str]:
        """Return Claude official setting sources for skills discovery."""
        return ["project"]

    def sync_to_claude_project_dir(self, cwd: str | Path) -> Path | None:
        """Mirror loaded skills into <cwd>/.claude/skills for standard runtime lookup."""
        if not self._skills:
            return None

        project_skills_dir = Path(cwd) / ".claude" / "skills"
        try:
            if project_skills_dir.exists():
                shutil.rmtree(project_skills_dir)
            project_skills_dir.mkdir(parents=True, exist_ok=True)

            for skill in self._skills:
                source_dir = skill.path.parent
                target_dir = project_skills_dir / source_dir.name
                shutil.copytree(source_dir, target_dir, dirs_exist_ok=True)
                self._ensure_script_exec_permissions(target_dir)
        except OSError as error:
            raise SkillLoadError(
                f"Failed to sync skills to Claude project dir: {project_skills_dir}",
            ) from error
        return project_skills_dir

    def get_skill(self, name: str) -> Skill | None:
        return self._skill_map.get(name)

    def _ensure_script_exec_permissions(self, skill_dir: Path) -> None:
        for script_file in skill_dir.rglob("*.sh"):
            if not script_file.is_file():
                continue
            current_mode = script_file.stat().st_mode
            # Mirror read bits to execute bits, keeping existing mode as-is otherwise.
            executable_mode = current_mode
            if current_mode & 0o400:
                executable_mode |= 0o100
            if current_mode & 0o040:
                executable_mode |= 0o010
            if current_mode & 0o004:
                executable_mode |= 0o001
            if executable_mode != current_mode:
                script_file.chmod(executable_mode)

    def _load_single(self, skill_file: Path) -> Skill:
        try:
            content = skill_file.read_text(encoding="utf-8")
        except OSError as error:
            raise SkillLoadError(f"Failed to read skill file: {skill_file}") from error

        matched = _FRONTMATTER_RE.match(content)
        if matched is None:
            raise SkillLoadError(f"Skill file missing YAML frontmatter: {skill_file}")

        frontmatter_raw, body = matched.group(1), matched.group(2)
        try:
            frontmatter = yaml.safe_load(frontmatter_raw) or {}
        except yaml.YAMLError as error:
            raise SkillLoadError(f"Skill frontmatter parse failed: {skill_file}") from error

        name = frontmatter.get("name")
        description = frontmatter.get("description")
        if not isinstance(name, str) or not name.strip():
            raise SkillLoadError(f"Skill name missing in file: {skill_file}")
        if not isinstance(description, str) or not description.strip():
            raise SkillLoadError(f"Skill description missing in file: {skill_file}")

        return Skill(
            name=name.strip(),
            description=description.strip(),
            content=body,
            path=skill_file,
        )
