locals {
  images = toset(["ladder-api", "ladder-worker"])
}

resource "aws_ecr_repository" "this" {
  for_each = local.images
  name     = each.key

  # MUTABLE: deploy.sh pushes :latest over and over. Switch to IMMUTABLE when
  # CI starts tagging by git SHA.
  image_tag_mutability = "MUTABLE"

  # Lets `terraform destroy` remove a repo that still holds images.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Without this every push is kept (and billed) forever.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep the last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
