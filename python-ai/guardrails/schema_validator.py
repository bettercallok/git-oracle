from pydantic import BaseModel, ValidationError

class ValidationResult(BaseModel):
    valid: bool
    errors: list[str]

def validate_schema(data: dict, schema_class: type[BaseModel]) -> ValidationResult:
    try:
        schema_class.model_validate(data)
        return ValidationResult(valid=True, errors=[])
    except ValidationError as e:
        errors = [f"{err['loc']}: {err['msg']}" for err in e.errors()]
        return ValidationResult(valid=False, errors=errors)
